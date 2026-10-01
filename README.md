# local-object-store-java

基于本地磁盘与内存元数据模拟的对象仓库，支持分片上传、内容去重、版本管理、
配额约束与按调用方隔离的访问控制。所有并发与重启场景下的结果一致且可解释。

## 运行与验证

```bash
mvn test          # 运行全部 60 个测试（单测日志打印关键输入与判定依据）
mvn spring-boot:run # 启动 REST 服务（默认 8080 端口）
```

REST 接口统一以 `X-Owner` 请求头标识调用方，主要端点：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads` | 创建分片上传会话，body 可选 `{"key","expectedParts"}` 声明总分片数 |
| GET | `/api/uploads/{uploadId}` | 查询会话与已提交分片（续传入口，会顺延过期） |
| PUT | `/api/uploads/{uploadId}/parts/{n}` | 提交分片，可带 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/complete` | 完成上传，可带整体 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/abort` | 中止并回收分片 |
| PUT | `/api/objects/{key}` | 整体写入 |
| GET | `/api/objects/{key}[?versionId=]` | 读取当前/指定版本（全量） |
| GET | `/api/objects/{key}?start=&end=&versionId=` | 区间读取（闭区间，`end` 省略表示到末尾），命中返回 206 |
| GET | `/api/objects/{key}` + 头 `Range: bytes=start-end` | 同上的标准 Range 形式 |
| GET | `/api/objects/{key}/versions` | 版本历史 |
| GET | `/api/objects` | 列举调用方可见的键 |
| DELETE | `/api/objects/{key}[?versionId=]` | 删除当前版本（标记）或指定版本（物理） |
| GET | `/api/usage` | 查询调用方配额总用量（存活版本 + 未完成上传分片） |
| GET | `/api/usage/unfinished-uploads` | 查询调用方当前未完成上传占用的分片字节 |

> 二进制写入（PUT 对象/分片）请带 `Content-Type: application/octet-stream`；
> 漏发请求体返回 400 `BAD_REQUEST`，不会被当成 500。

## 存储布局

根目录由 `objectstore.root-dir` 配置（默认 `data/object-store`）：

```
<objectstore.root-dir>/
├── blobs/<sha256>        # 内容寻址的数据块，按 SHA-256 命名，相同内容仅存一份
├── content-index.ser     # 块引用计数与大小索引（原子写：临时文件 + rename）
└── metadata.ser          # 对象/版本/上传会话的元数据快照（同样原子落盘）
```

所有元数据修改在内存锁内执行并立即原子落盘，进程重启后由两个 `.ser`
文件完整恢复：已提交对象、版本历史、未完成上传及其分片均不错乱。

## 去重规则

- 数据块以内容 SHA-256 为键，写入时命中既有块则只增加引用计数（`dedup hit` 日志）。
- 引用计数持久化在 `content-index.ser`，重启后保持一致。
- 释放路径：物理删除指定版本、中止上传会话。引用归零才物理删除块文件，
  因此相同内容被多次写入后仍可独立读取与删除，不会被提前回收。
- 分片上传完成时，分片块的引用由上传会话**转移**给新版本，不重复计数。

## 版本规则

- 每次写入（`putObject` 或 `completeUpload`）在对象版本列表尾部追加一个不可变版本；
  并发覆盖同一对象在元数据锁内串行化，读取方只能看到完整且自洽的版本
  （读取时对拼接结果重新校验整体 SHA-256）。
- `DELETE` 不带 `versionId`：追加删除标记版本（S3 语义），当前版本不可读、
  不出现在列表中，但历史版本内容仍可读取。
- `DELETE` 带 `versionId`：物理移除该版本并释放其块引用，不影响其他版本；
  版本列表清空后对象元数据一并移除。
- 重复 `complete` 同一会话幂等返回首次产生的版本，不会生成重复版本。

## 分片上传规则

- 分片按 `partNumber` 排序拼接，乱序提交得到确定结果。
- 同一 `partNumber` 重复提交：内容相同 → 幂等成功且不重复计入配额；
  内容不同 → `CONFLICT`。
- 会话过期时间为滑动窗口：任何会话交互（查询/提交分片）都会顺延，
  已上传分片不会因超时丢失；客户端可凭 `uploadId` 跨会话续传。
- 分片可带 `X-Expected-Sha256` 校验，不一致以 `CHECKSUM_MISMATCH` 拒绝且不入库；
  完成时可带整体校验和，不一致同样拒绝且**不留下可读对象**，会话保持
  `ACTIVE` 供修正重试。

### 可选的分片总数声明（expectedParts）

创建会话时可在 body 中声明本次总分片数：`{"key":"doc","expectedParts":3}`。

- 声明是**可选**的：不传或省略 `expectedParts` 时完全沿用老流程
  （任意非空分片集合即可完成，分片号允许不连续），老客户端无需任何改造。
- 声明后，完成时必须 `1..expectedParts` 每一个序号都到齐，否则以
  `MISSING_PARTS`（HTTP 409）拒绝，响应 `details` 为**缺失分片号的有序列表**，
  例如 `{"code":"MISSING_PARTS","message":"...","details":[2,5]}`。
  该原因与「内容对不上（`CHECKSUM_MISMATCH`）」「会话状态不对（`INVALID_STATE`）」
  通过不同错误码明确区分；被缺片拒绝的会话仍是 `ACTIVE`，补齐后可直接重试完成。
- 提交序号大于 `expectedParts` 的分片在写入前以 `BAD_REQUEST` 拒绝、不占容量。
- `expectedParts` 取值范围 `1..objectstore.max-parts`，创建时非法即 `BAD_REQUEST`。
- 该声明随会话持久化，重启后续传与缺片判定保持一致。
- 未声明分片数的**历史会话**反序列化后 `expectedParts=null`，行为不变。

## 未完成上传的空闲过期与容量回收

- 配置 `objectstore.upload-idle-ttl-millis`（默认 86400000=24h，`<=0` 关闭过期）：
  会话自**最近一次交互**（创建/查询/续传/上报分片）起，连续空闲**严格超过**
  该阈值才判定过期；空闲时长恰好等于阈值不算过期。
- 交互（含查询与幂等重发相同分片）会滑动续期并刷新 `lastInteractAtMillis`，
  因此正在续传/上报的会话不会被误回收。
- **一次交互只做一次空闲判定（关键并发语义）**：续传/上报/完成/查询/中止/用量
  统计在进入元数据锁后，会先对该调用方做**一次且仅一次**过期清扫，之后整个处理
  流程不再重新读取时钟、不再触发嵌套清扫。这样即使墙钟在请求处理过程中（例如
  磁盘 fsync 抖动、GC 停顿）恰好跨过阈值，结果也是确定的二选一：
  - 请求在入口判定时**未过期** → 整次请求成功、会话滑动续期、本次写入的内容
    **如实计入**用量与物理占用；处理中途不会被自己或后台任务回收；
  - 请求在入口判定时**已过期**（或并发请求/后台扫描刚完成回收）→ 明确返回
    `UPLOAD_EXPIRED`（HTTP 410），不写入任何内容、不留垃圾块。

  不会再出现旧版本里“请求返回成功，事后会话却已过期、内容既不计入占用又
  回收不掉”的中间态（根因是旧实现在锁内先判定一次、配额检查里的嵌套清扫又
  采样一次时钟，两次读数跨过阈值就会撕裂结果）。
- **多个请求挤在过期点上的最终结果**：全部请求在同一把元数据锁上串行化，由
  “谁先拿到锁”决定且只有两类收敛结局：
  - 空闲尚未超过阈值时到达的请求 → 全部成功并把会话续上；
  - 已有一个请求（或后台扫描）完成过期回收后到达的请求 → 全部明确 410。

  若请求在越过阈值前后分批到达，则先到的批次成功、回收发生后到达的批次全部
  410，已成功的分片在那次过期中被**恰好一次**释放。任何时刻
  `GET /api/usage`、`/api/usage/unfinished-uploads` 的逻辑口径都等于底层内容
  存储的真实物理占用：一次过期只回收一次，既不重复扣减也不漏回收。
- **无后续请求也会回收（后台回收器）**：除惰性清扫外，内置 `UploadExpiryReaper`
  守护线程按 `objectstore.upload-sweep-interval-millis`（默认 1000ms）固定间隔
  在元数据锁内全量扫描所有调用方。调用方彻底放弃上传、再也不发请求时，容量最迟
  在“空闲阈值 + 一个扫描间隔”内被真正释放。它与请求路径共用同一把锁和同一套
  过期判定，回收幂等、可反复执行；正在持续交互的会话因滑动续期不会被误回收。
  关闭过期（`upload-idle-ttl-millis<=0`）时后台线程不调度。
- 过期是**惰性清扫 + 后台扫描**双保险：任何会话操作、用量统计进入元数据锁后也会
  先清扫。过期时一次性：释放该会话全部分片的内容引用（引用归零则物理删除块）、
  清空分片表、状态置为 `EXPIRED` 并原子落盘。
- 过期后会话**不能**再查询（410）、续传（410）或完成（410），占用容量归零，
  且过期状态与已释放容量在重启后保持，不会复活或被重新计入。
- **旧会话兼容**：`lastInteractAtMillis` 是本特性引入的字段，更早版本持久化的
  会话快照里没有该字段；反序列化时自动回填为创建时间，启用空闲过期后这些历史
  会话也不会被立刻误判过期。未声明分片数的老流程（`expectedParts=null`）行为
  完全不变，调用方无需改造。
- 中止（`abort`）与完成（`complete`）的会话不受空闲清扫影响。
- `GET /api/usage/unfinished-uploads` 返回调用方当前未完成（`ACTIVE`）上传占用的
  分片字节；该数字与 `/api/usage` 中「未完成上传分片」口径、以及配额判断、底层
  物理占用完全一致（入口都会先做同一次清扫）。

## 区间读取（Byte Range）

当前版本与指定版本均支持按字节区间读取。

- 两种等价形式：
  - 查询参数：`GET /api/objects/{key}?start=10&end=19[&versionId=...]`，
    `end` 省略表示一直读到对象末尾。
  - 标准请求头：`Range: bytes=10-19`（`bytes=10-` 表示到末尾）。
- 区间为**双闭区间** `[start,end]`，字节下标从 0 开始。命中返回
  **206 Partial Content**，并带 `Content-Range: bytes start-end/total`
  与 `Accept-Ranges: bytes`；返回片段与把整段读出后切片的结果一致。
- 区间读取先整段读出并重新校验整体 SHA-256，**再切片**，因此不会绕过内容校验；
  跨分片拼接的对象同样适用（可落在分片边界两侧）。
- 非法区间（`start<0`、`end<-1`、`end>=0 且 end<start`、Range 头语法错误）
  返回 400 `BAD_REQUEST`；合法但越界（`start>=size` 或 `end>=size`）
  返回 416 `RANGE_NOT_SATISFIABLE`，并在 `details` 中回带对象总长度。
- 权限与版本判定与全量读取完全一致：越权一律 404（不暴露对象是否存在），
  删除标记版本/不存在版本按 404 处理。

## 配额策略

- `objectstore.quota-bytes`：每个调用方的逻辑字节配额，统计口径为
  「存活版本字节 + 进行中上传的分片字节」。完成上传时引用转移，口径不变；
  中止或物理删除立即释放。
- `objectstore.max-part-size`：单分片字节上限，超限返回 `PART_SIZE_EXCEEDED`。
- `objectstore.max-parts`：分片序号上限（也是 `expectedParts` 上限）。
- `objectstore.upload-ttl-millis`：滑动有效期，任何交互按此时长顺延 `expiresAt`。
- `objectstore.upload-idle-ttl-millis`：连续空闲过期阈值，超过即回收（见上文）。
- `objectstore.upload-sweep-interval-millis`：后台回收扫描间隔，无后续请求时最迟
  在「空闲阈值 + 该间隔」内释放容量（默认 1000ms）。
- 超限在写入前检查，失败后无残留分片、不占用未释放容量。

## 访问控制

- 所有对象与上传会话按 `owner` 隔离；越权读、删、列举一律返回 `NOT_FOUND`，
  与「真不存在」不可区分，不泄露对象存在性。
- 元数据查询（版本历史）与内容下载走同一 `requireObject` 判定，结果一致。

## 异常归一化

所有错误映射为 `StoreException.Code`，REST 层返回 `{code, message}`，
底层异常不透出、不静默吞掉：

| Code | HTTP | 含义 |
|---|---|---|
| `NOT_FOUND` | 404 | 不存在或越权（含删除标记版本） |
| `BAD_REQUEST` | 400 | 参数非法（含区间语法非法、声明数越界、缺请求体） |
| `CHECKSUM_MISMATCH` | 422 | 分片/整体校验和不一致 |
| `QUOTA_EXCEEDED` / `PART_SIZE_EXCEEDED` | 413 | 配额/分片大小超限 |
| `CONFLICT` | 409 | 同序号分片内容冲突 |
| `MISSING_PARTS` | 409 | 声明了分片总数但完成时有缺口，`details` 为缺失分片号列表 |
| `INVALID_STATE` | 409 | 会话已中止/已完成等非法状态 |
| `UPLOAD_EXPIRED` | 410 | 会话连续空闲过期，容量已回收，不可续传/完成 |
| `RANGE_NOT_SATISFIABLE` | 416 | 区间合法但越界，`details` 回带对象总长度 |
| `STORAGE_FAILURE` | 500 | 底层存储失败（含内容损坏、丢失） |

## 测试覆盖

`src/test/java/.../tests/` 下 60 个测试，日志打印关键输入与判定依据：

- `MultipartUploadTest`：乱序分片、重复分片（幂等/冲突）、跨会话续传、
  分片与整体校验失败、完成幂等、中止释放配额。
- `DeclaredPartsTest`：声明分片数后单缺口/多缺口完成被 `MISSING_PARTS` 拒绝且
  `details` 精确给出缺口；补齐后完成；超过声明序号/非法声明数 `BAD_REQUEST`；
  未声明的老流程（允许非连续序号）保持原行为；乱序拼接仍按序号对齐。
- `UploadExpiryTest`：连续空闲过期回收容量并阻断查询/续传/完成（`UPLOAD_EXPIRED`）、
  交互滑动续期不过期、边界（空闲恰好等于阈值不过期、再超 1ms 过期）、
  已完成/已中止会话不被清扫、8 线程并发上报分片与过期的竞争。
- `UploadExpiryCollisionTest`（本轮新增，可控时钟）：
  - **请求处理中跨过阈值**（脚本化时钟让同一次请求的两次时钟读数分别落在
    “未过期/已过期”两侧）：修复后请求必须确定地成功并续期，新分片如实计入，
    逻辑用量==物理占用；之后真正超时仍能唯一回收。该用例在旧实现上稳定复现
    “返回成功却已过期、物理块游离”的故障。
  - 边界（空闲==ttl）8 线程并发上报 → 全部成功续上；越过阈值 1ms 并发上报 →
    全部明确 410、只回收一次、物理块归零；
  - 先成功后让回收与剩余上报相撞 → 已成功分片在那次过期中恰好一次释放；
  - 两轮（先并发成功、再推进时钟并发失败）跨轮无残留；
  - **后台回收**：调用方再无任何请求，回收到点真正释放；持续查询的会话不被误回收；
    回收结果重启后保持；旧快照缺失交互时间字段时回填创建时间、不被立即误过期；
  - **真实墙钟压测**（15 轮 × 8 上报线程 + 后台扫描线程，随机延迟）：每轮断言
    每个请求要么成功要么明确过期、逻辑用量始终==物理占用、终态无游离块。
- `RangeReadTest`：当前/指定版本的合法区间与整读对齐、`end=-1` 到末尾、
  非法区间 400 与越界 416 原因可区分（越界带总长度）、删除标记/不存在 404、
  越权区间与全量一致返回 404、跨分片边界区间一致。
- `DedupTest`：相同内容多键写入只存一份、独立删除、引用归零回收、
  分片与整体写入之间去重。
- `VersioningConcurrencyTest`：并发覆盖全部成为完整版本、并发完成收敛到
  单一版本、删除标记与指定版本删除的作用范围。
- `QuotaTest`：配额/分片大小超限的拒绝原因与无残留、配额按调用方隔离、
  删除后配额释放。
- `AccessControlTest`：越权读/删/列与「不存在」不可区分、元数据与下载判定一致。
- `RestartRecoveryTest`：重启后已提交对象、未完成上传、引用计数与配额用量一致；
  过期状态与容量回收重启后保持、不复活。
- `ConcurrentMixedOpsTest`：并发写删同一键收敛（版本数 = 成功写数 + 成功删数）、
  并发上传不同键互不干扰。

## 本地验证新增能力（端到端）

以短空闲阈值启动，便于观察过期（后台回收默认每 1s 扫描一次）：

```bash
mvn -DskipTests package
java -jar target/local-object-store-java-1.0-SNAPSHOT.jar \
  --objectstore.root-dir=/tmp/los-demo \
  --objectstore.quota-bytes=10000 \
  --objectstore.upload-idle-ttl-millis=3000 --objectstore.upload-ttl-millis=3000 \
  --objectstore.upload-sweep-interval-millis=1000
```

声明分片数与缺片（二进制写入请带 octet-stream 头）：

```bash
H='-H Content-Type:application/octet-stream'
U=$(curl -s -X POST localhost:8080/api/uploads -H 'X-Owner: alice' \
  -H 'Content-Type: application/json' -d '{"key":"doc","expectedParts":3}' \
  | sed -E 's/.*"uploadId":"([^"]+)".*/\1/')
printf AAAA > a; printf CCCC > c; printf BBBB > b
curl -s -X PUT localhost:8080/api/uploads/$U/parts/1 -H 'X-Owner: alice' $H --data-binary @a
curl -s -X PUT localhost:8080/api/uploads/$U/parts/3 -H 'X-Owner: alice' $H --data-binary @c
curl -s -X POST localhost:8080/api/uploads/$U/complete -H 'X-Owner: alice'
# -> {"code":"MISSING_PARTS",...,"details":[2]}
curl -s -X PUT localhost:8080/api/uploads/$U/parts/2 -H 'X-Owner: alice' $H --data-binary @b
curl -s -X POST localhost:8080/api/uploads/$U/complete -H 'X-Owner: alice'
curl -s localhost:8080/api/objects/doc -H 'X-Owner: alice'   # AAAABBBBCCCC
```

区间读取（206 + Content-Range）：

```bash
curl -i 'localhost:8080/api/objects/doc?start=4&end=7' -H 'X-Owner: alice'
curl -i localhost:8080/api/objects/doc -H 'X-Owner: alice' -H 'Range: bytes=8-11'
```

过期与占用：建会话传一个分片后静置 >3 秒，再观察
`GET /api/usage/unfinished-uploads`（归零）与后续操作返回 410 `UPLOAD_EXPIRED`；
即使期间**不发任何请求**，后台回收器也会在约 3~4 秒内把容量真正释放
（服务日志可见 `uploadExpired ... releasedBytes=` 与 `sweepAllExpired reaped=`）。
期间每秒 `GET /api/uploads/{id}` 则会话持续 ACTIVE（滑动续期）。

相撞语义的本地验证：

```bash
# 只跑可控时钟下的相撞/回收测试（含“请求处理中跨过阈值”复现）
mvn test -Dtest=UploadExpiryCollisionTest
# 单测日志会打印每个关键输入（ttl、时钟读数、8 线程/扫描线程同时触发）与判定依据
# （stored/expired 计数、最终 state、逻辑用量 logical 与物理占用 physical）。
```
