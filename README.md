# local-object-store-java

基于本地磁盘与内存元数据模拟的对象仓库，支持分片上传（含分片总数声明）、内容去重、
版本管理、配额约束、未完成上传的空闲过期与容量回收、按字节区间读取，以及按调用方隔离的
访问控制。所有并发与重启场景下的结果一致且可解释。

## 运行与验证

```bash
mvn test             # 运行全部 53 个测试（单测日志打印关键输入与判定依据）
mvn spring-boot:run  # 启动 REST 服务（默认 8080 端口）
```

REST 接口统一以 `X-Owner` 请求头标识调用方，主要端点：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads` | 创建分片上传会话，请求体可选 `expectedTotalParts` |
| GET | `/api/uploads/{uploadId}` | 查询会话与已提交分片（续传入口），顺延过期时间 |
| PUT | `/api/uploads/{uploadId}/parts/{n}` | 提交分片，可带 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/complete` | 完成上传，可带整体 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/abort` | 中止并回收分片 |
| PUT | `/api/objects/{key}` | 整体写入 |
| GET | `/api/objects/{key}[?versionId=&start=&end=]` | 整段读取，或加 `start`/`end` 区间读取（206） |
| GET | `/api/objects/{key}/versions` | 版本历史 |
| GET | `/api/objects` | 列举调用方可见的键 |
| DELETE | `/api/objects/{key}[?versionId=]` | 删除当前版本（标记）或指定版本（物理） |
| GET | `/api/usage` | 用量：`usedBytes`/`objectBytes`/`incompleteUploadBytes` |

### 分片总数声明（可选）

- 创建会话时请求体可带 `expectedTotalParts`：

  ```json
  {"key": "docs/a.bin", "expectedTotalParts": 3}
  ```

  不带或为 `null` 即沿用老流程，行为完全不变；已存在的老会话同样保持老行为，
  调用方无需任何改造。
- 声明后：
  - 只有 **1 号到 N 号全部到齐** 才允许完成；中间有缺口一律拒绝，错误码
    `MISSING_PARTS`（HTTP 409），消息明确列出缺失分片号，例如
    `分片未到齐: 声明总数=4 缺少分片号=[2]`。
  - 提交序号大于声明总数的分片返回 `BAD_REQUEST`（HTTP 400）。
  - 缺口与内容校验失败区分明确：**先判缺口，再校验内容**，因此缺片时即使同时带了
    错误的整体哈希，也返回 `MISSING_PARTS` 而不是 `CHECKSUM_MISMATCH`。
  - 缺片拒绝不关闭会话，补齐缺失分片后可直接重试完成。
- 未声明的会话保留老语义：只要有分片即可完成，按实际到齐的分片序号拼接。

### 未完成上传的空闲过期与容量回收

- 会话过期时间是**滑动空闲窗口**（配置 `objectstore.upload-ttl-millis`，默认 24h）：
  任意一次查询、上报分片、续传、完成等交互都会把过期时刻顺延为“当前时刻 + 阈值”。
  只有**连续空闲**超过阈值才算过期，活跃交互中的会话不会被回收。
- 过期判定有两条路径，且都在元数据锁内一次完成：
  - 后台扫描：`UploadExpirySweeper` 按 `objectstore.expiry-sweep-interval-millis`
    （默认 60s，设为 `<=0` 关闭后台扫描）周期回收；
  - 惰性判定：访问具体会话时若已到点，当场回收。
- 回收是真实的：逐分片释放内容块引用（归零物理删除）、清空分片表、状态置为
  `EXPIRED` 并原子落盘。过期后查询、续传、完成统一返回 `UPLOAD_EXPIRED`
  （HTTP 410，区别于普通状态非法的 409）；对已过期会话调用中止按幂等成功处理。
- **并发确定性**：回收与续传/上报分片互斥（同一把元数据锁），结果只有两种——
  交互先持锁则成功并把会话续上（同内容并发提交全部幂等成功，容量只计一次）；
  回收先持锁则会话 `EXPIRED`、容量彻底释放，其余交互全部得到 `UPLOAD_EXPIRED`。
  不会出现容量重复计算、漏算或永远回收不掉。`UploadExpiryTest` 以 12 线程 ×
  30 轮临界竞争验证逻辑用量恒等于物理占用。
- 容量口径与配额一致：

  | `/api/usage` 字段 | 含义 |
  |---|---|
  | `incompleteUploadBytes` | 该调用方所有 ACTIVE 会话的分片字节（过期/中止/完成均不计） |
  | `objectBytes` | 存活版本字节 |
  | `usedBytes` | `objectBytes + incompleteUploadBytes`，即配额判定口径 |

### 按字节区间读取

- `GET /api/objects/{key}?start=&end=`，`start`/`end` 为**闭区间、0 基**，必须同时提供；
  加 `versionId` 读取指定版本的区间。
- 合法区间返回 `206 Partial Content`，带 `Content-Range: bytes start-end/total`
  与 `Accept-Ranges: bytes`，切片结果与“整段读出后按相同下标切片”逐字节一致。
- 边界规则：
  - `start == end`（单字节）、`[0, length-1]`（整段）均合法；
  - `end >= length` 但 `start < length`：末尾自动截断到 `length-1`，仍返回 206；
  - `start >= length`：返回 `RANGE_NOT_SATISFIABLE`（HTTP 416）；
  - 缺参数、负数、`start > end`：返回 `BAD_REQUEST`（HTTP 400），与越界明确区分。
- 区间读取复用整段读取路径：先完整拼接并重校整体 SHA-256 再切片，不绕过内容校验；
  权限判定与整段读取完全一致，越权返回 `NOT_FOUND`，与“对象不存在”不可区分。

### 向后兼容范围

- `POST /api/uploads` 不传 `expectedTotalParts` 与旧请求体完全兼容（JSON 反序列化
  缺省为 `null`）。
- 未声明会话的完成、缺片拼接、续传、幂等、中止语义与旧版本一致。
- 区间读取只在显式带 `start`/`end` 时生效；不带参数时仍是整段 200 读取。
- `/api/usage` 在原有 `usedBytes` 之外**增加**字段，不改变原有字段含义。
- 元数据/内容快照新增字段均可空，旧类已显式声明 `serialVersionUID`，
  重启恢复（含 ACTIVE 续传、EXPIRED 状态、声明分片数）不受影响。

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
- 会话过期时间为滑动空闲窗口：任何会话交互（查询/提交分片等）都会顺延，
  连续空闲超过阈值才过期回收（详见上文“空闲过期与容量回收”）；
  客户端可凭 `uploadId` 跨会话续传。
- 可选的 `expectedTotalParts` 声明改变完成条件（见上文“分片总数声明”）；
  不声明时保持老行为。
- 分片可带 `X-Expected-Sha256` 校验，不一致以 `CHECKSUM_MISMATCH` 拒绝且不入库；
  完成时可带整体校验和，不一致同样拒绝且**不留下可读对象**，会话保持
  `ACTIVE` 供修正重试。缺口校验先于整体校验。

## 配额策略

- `objectstore.quota-bytes`：每个调用方的逻辑字节配额，统计口径为
  「存活版本字节 + 进行中（ACTIVE）上传的分片字节」。完成上传时引用转移，口径不变；
  中止、物理删除、空闲过期立即释放。
- `objectstore.max-part-size`：单分片字节上限，超限返回 `PART_SIZE_EXCEEDED`。
- `objectstore.max-parts`：分片序号上限（同时约束 `expectedTotalParts`）。
- `objectstore.upload-ttl-millis`：未完成会话滑动空闲过期阈值（默认 24h）。
- `objectstore.expiry-sweep-interval-millis`：后台过期扫描间隔（默认 60s，`<=0` 关闭）。
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
| `BAD_REQUEST` | 400 | 参数非法（含区间格式错误、超声明分片号、声明值越界） |
| `CHECKSUM_MISMATCH` | 422 | 分片/整体校验和不一致 |
| `QUOTA_EXCEEDED` / `PART_SIZE_EXCEEDED` | 413 | 配额/分片大小超限 |
| `CONFLICT` | 409 | 同序号分片内容冲突 |
| `MISSING_PARTS` | 409 | 声明分片总数后完成时仍有缺口（消息含缺失分片号） |
| `INVALID_STATE` | 409 | 会话已中止/已完成等非法状态 |
| `UPLOAD_EXPIRED` | 410 | 会话空闲超时已回收，不能续传/完成（明确区别于 INVALID_STATE） |
| `RANGE_NOT_SATISFIABLE` | 416 | 区间起点越过对象末尾 |
| `STORAGE_FAILURE` | 500 | 底层存储失败（含内容损坏、丢失） |

## 测试覆盖

`src/test/java/.../tests/` 下 53 个测试，日志打印关键输入与判定依据：

- `MultipartUploadTest`：乱序分片、重复分片（幂等/冲突）、跨会话续传、
  分片与整体校验失败、完成幂等、中止释放配额。
- `DeclaredPartsTest`：声明后到齐完成、缺片返回 `MISSING_PARTS` 并报告缺口、
  缺口与校验失败/状态非法区分、超声明序号拒绝、未声明老流程兼容。
- `UploadExpiryTest`：空闲过期真实释放（逻辑/物理占用一致）、滑动交互续期、
  过期后查询/续传/完成均为 `UPLOAD_EXPIRED`、12 线程 × 30 轮回收竞争
  （校验逻辑用量恒等于物理占用、终态唯一）、完成/中止会话不被扫描、
  未完成上传占用查询与配额口径一致。
- `RangeReadTest`：区间切片与整段读取一致、首尾/单字节边界、end 超长截断、
  start 越界 416、格式非法 400、指定版本区间、越权与不存在不可区分、
  删除标记与历史版本的区间读取。
- `DedupTest`：相同内容多键写入只存一份、独立删除、引用归零回收、
  分片与整体写入之间去重。
- `VersioningConcurrencyTest`：并发覆盖全部成为完整版本、并发完成收敛到
  单一版本、删除标记与指定版本删除的作用范围。
- `QuotaTest`：配额/分片大小超限的拒绝原因与无残留、配额按调用方隔离、
  删除后配额释放。
- `AccessControlTest`：越权读/删/列与「不存在」不可区分、元数据与下载判定一致。
- `RestartRecoveryTest`：重启后已提交对象、未完成上传、引用计数与配额用量一致。
- `ConcurrentMixedOpsTest`：并发写删同一键收敛（版本数 = 成功写数 + 成功删数）、
  并发上传不同键互不干扰。

## 本地端到端验证（HTTP）

单测之外，可启动真实服务把新增流程完整走一遍（示例用短 TTL 观察过期）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --server.port=8088 \
  --objectstore.root-dir=data/http-e2e \
  --objectstore.upload-ttl-millis=4000 \
  --objectstore.expiry-sweep-interval-millis=1000"

# 1) 声明分片数并复现缺片完成（注意分片上传需显式 Content-Type）
curl -s -X POST localhost:8088/api/uploads -H 'X-Owner: alice' \
  -H 'Content-Type: application/json' \
  -d '{"key":"a.bin","expectedTotalParts":3}'
curl -s -X PUT localhost:8088/api/uploads/<id>/parts/1 \
  -H 'X-Owner: alice' -H 'Content-Type: application/octet-stream' --data-binary p1.bin
# ... 缺 2 号直接 complete -> 409 MISSING_PARTS，消息含 缺少分片号=[2]

# 2) 区间读取
curl -s -D - "localhost:8088/api/objects/a.bin?start=3&end=7" -H 'X-Owner: alice'
# -> 206，Content-Range: bytes 3-7/<total>

# 3) 过期回收：停止交互 > TTL 后查询/续传/完成 -> 410 UPLOAD_EXPIRED；
#    /api/usage 中 incompleteUploadBytes 归零；持续每 2s GET 会话可续期不过期。
curl -s localhost:8088/api/usage -H 'X-Owner: alice'
```
