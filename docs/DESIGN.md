# 本地对象存储：分片上传、去重与版本管理

用本地磁盘 + 内存元数据（JSON 落盘）模拟对象仓库，覆盖分片上传/中断续传、内容去重、
多版本并发、容量配额、租户隔离、重启恢复与异常归一化。

---

## 1. 存储布局

根目录由 `objectstore.root-dir` 指定（默认 `data/objectstore`）：

```
<root>/
├── blobs/                         内容寻址数据块（不可变，写入即终身）
│   └── ab/
│       └── cd/
│           └── abcdef…（64 位完整 SHA-256，前 4 位两级分桶）
├── uploads/                       分片上传的在途数据
│   └── <uploadId>/
│       ├── incoming-*.tmp         正在接收的分片（崩溃后重启清理）
│       ├── part-1 … part-N        已接收并校验通过的分片
│       └── object-*.tmp           complete 时拼接整对象的临时文件（崩溃后清理）
├── tmp/                           一次性写入（PUT）的临时区，与 blobs 同文件系统
└── meta/                          元数据（每实体一个 JSON，临时文件 + 原子 rename）
    ├── uploads/<uploadId>.json
    ├── objects/<tenant>/<URLEncode(objectKey)>.json   含该对象的全部版本（有序）
    └── blobs/<sha256>.json        blob 大小与引用计数（引用计数可从版本表重建）
```

关键设计：

- **临时文件 + 原子 rename**：所有数据文件与元数据文件都先写临时文件再原子改名，
  读方永远不会看到半个文件；因此不存在“可读的半成品对象”。
- **内容寻址**：blob 文件名即内容 SHA-256，相同内容天然指向同一物理块。
- **内存索引 + JSON 持久化**：热路径走内存（TreeMap 有序、确定性遍历），
  每次变更整文件原子替换；进程重启时从 `meta/` 全量重建。

---

## 2. 分片上传与续传规则

### 2.1 生命周期

`initiate`（ACTIVE）→ 若干次 `uploadPart`（可乱序）→ `complete`（COMPLETED）
或 `abort`（ABORTED）。

- 发起时必须声明 `totalParts`（1..`max-parts-per-upload`，默认 1000）。
- 会话按 `uploadId` 隔离，**跨会话（进程重启）续传**：分片在磁盘、会话在 JSON，
  重启后以“磁盘分片为准”重建会话（分片号、大小、etag），已传内容不丢失、不重复计费。
- 每个会话有独立的分片大小上限（发起时可覆盖默认值）。

### 2.2 确定性结果矩阵

| 情形 | 结果 |
|---|---|
| 分片乱序提交（如先 3 后 1） | 正常接收，按分片号排序，完成时按 1..N 拼接 |
| 重复提交同号分片，**内容相同**（etag+大小一致） | 幂等，返回原回执，**不重复计入容量** |
| 重复提交同号分片，**内容不同** | `CONFLICT` 拒绝，原分片保留，提交内容丢弃，容量不变 |
| 分片内容与声明的 `X-Part-Sha256` 不一致 | `CHECKSUM_MISMATCH`，分片不落盘不计费，会话保持 ACTIVE 可重传 |
| 分片字节数超过该会话上限 | `QUOTA_EXCEEDED`（消息含 `part size exceeded`） |
| 分片未齐就 complete | `PARTS_INCOMPLETE`（消息含 expected/received/缺失号），不是终态，可补齐后重试 |
| complete 时整对象 SHA-256 与预期不符 | `CHECKSUM_MISMATCH`：拼接临时文件被删，**无版本产生**，在途分片保留以便带正确摘要重试 |
| 对已 COMPLETED 会话再次 complete | 幂等，返回同一个已提交版本，不产生新版本、不重复计费 |
| abort | 删除整个上传目录、释放全部在途容量、会话置 ABORTED；重复 abort 幂等；abort 后再传分片 → CONFLICT |
| 重启时发现 ACTIVE 会话但磁盘分片目录为空 | 判定为崩溃残留，置 ABORTED 并清理目录 |
| 磁盘有分片目录但无会话元数据（孤儿目录） | 重启对账时删除，且不计入容量 |

### 2.3 完成时的原子提交

1. 在会话目录内顺序拼接 part-1..part-N，流式计算整对象 SHA-256；
   拼接期间顺带核对每个分片磁盘大小与回执一致。
2. 整对象摘要与预期（complete 参数优先，其次发起时声明）不符则删除拼接文件并拒绝。
3. 进入 **blob 全局锁**：
   - blob 已存在（去重命中）：零新增容量，refCount+1；
   - blob 不存在：先 `reserve(size)` 做容量预检，再原子 rename 进 `blobs/`；
     任何失败都 `release` 回滚。
4. 写版本元数据（在 **对象锁** 保护下追加版本）成功后：
   释放该会话全部在途分片占用、会话置 COMPLETED、删除上传目录。
5. 元数据写失败时按“是否新建 blob”分别回滚物理块/引用计数/容量，恢复到调用前状态。

---

## 3. 去重规则

- blob 键 = 内容 SHA-256；**跨对象、跨版本、跨分片/一次性写入**都去重。
- `BlobRecord.refCount` = 引用该 blob 的**对象版本数**（不是对象数，也不是写入次数）。
- 容量只对**唯一 blob** 计费：相同内容写 3 次，物理容量只算 1 份，refCount=3，
  三个版本各自独立可读、可删。
- 引用计数**不依赖持久化计数值**：启动时从版本表全量重算（防止任何计数漂移）。
- **最后一个引用消失才回收**：删版本 → refCount-1；归零时删除 blob 文件与元数据、
  释放容量。仍被其他版本/对象引用时绝不动数据。
- 重启发现“有 blob 文件但零版本引用”（元数据写入前崩溃）按孤儿块回收；
  “有版本引用但 blob 文件缺失”报 `STORAGE_FAULT`（数据安全事件，不静默）。

---

## 4. 版本与并发规则

- 每次成功的 `PUT` 或 `complete` 都为 `(tenant, objectKey)` 追加一个**不可变版本**
  （versionId 随机，createdAtMillis 排序），旧版本保持可读。
- 默认读取（不带 versionId）返回最新版本；按 versionId 读取指定历史版本。
- **锁编排**（锁序固定：对象/上传锁 → blob 锁，避免死锁）：
  - 对象条带锁 `obj|tenant|key`：串行化同对象的 PUT / complete / 删除 →
    版本列表的追加是“整体替换”，读者只会看到完整自洽的版本，没有半写状态；
    不同对象使用不同锁，可并发。
  - 上传条带锁 `upl|<uploadId>`：串行化同一会话的分片提交/complete/abort →
    重复分片、并发完成结果确定。
  - 全局 blob 锁：串行化 refCount 与容量联动。
- 读操作无锁：版本不可变 + 原子 rename 保证直接读即可。
- 删除作用域明确：
  - `DELETE ?objectKey=k&versionId=v`：**只删一个版本**，同名其他版本与其他对象不受影响；
  - `DELETE ?objectKey=k`：删除**该租户该 key 的全部版本**，返回 204；
  - 删除绝不会跨越租户或 key 的边界。
  - 删除最新版本后，默认读取自动回退到上一完整版本；最后一个版本删完则 key 消失。

---

## 5. 配额策略

配置项：

| 配置 | 默认 | 含义 |
|---|---|---|
| `objectstore.capacity-bytes` | 100 MiB | 全仓逻辑容量上限 |
| `objectstore.default-max-part-size` | 8 MiB | 单个分片默认字节上限（发起上传可覆盖） |
| `objectstore.max-parts-per-upload` | 1000 | 单次上传分片数上限 |

- **占用口径**：`唯一 blob 字节数 + 在途分片字节数`（拼接临时文件不做逻辑计费，
  其生命周期极短且完成时立刻用 blob 置换分片占用）。
- CAS 原子计数：`reserve` 超限即拒绝（错误消息给出 capacity/used/requested），
  `release` 下溢视为服务端故障。
- 任何失败路径严格回滚（见 §2.3），保证**不残留部分分片、不占用未释放容量**。
- 纯去重命中（内容已存在）零新增容量：剩余空间不足时写入已有内容仍成功。
- 重启后容量以磁盘事实重建：`存活 blob 大小 + ACTIVE 会话在途分片大小`。
- `GET /api/objects/quota` 返回 `capacityBytes / usedBytes / availableBytes`。

两类大小拒绝可从消息区分：`part size exceeded`（单分片上限）vs `capacity exceeded`
（全仓容量），错误类型同为 `QUOTA_EXCEEDED`。

---

## 6. 租户隔离

- 调用方身份由请求头 `X-Tenant-Id` 提供；元数据按租户分目录、内存按租户分索引。
- 元数据查询与内容下载走**同一份归属判定**：
  会话按 uploadId 查出后比对 tenant；对象版本索引直接以 tenant 为一级键。
- 越权（读/删/列举/操作他人上传会话）与“真的不存在”**统一返回 `NOT_FOUND`**，
  响应文案不含 forbidden/unauthorized/tenant 字样，不泄露对象是否存在。
- 同名 key 可在不同租户各自独立存在；物理内容相同仍共享 blob（隔离与去重同时成立）。

---

## 7. 异常归一化

| 错误类型 | 触发场景 | HTTP |
|---|---|---|
| `NOT_FOUND` | 对象/版本/会话不存在，或越权访问 | 404 |
| `INVALID_ARGUMENT` | 空 key、totalParts 越界、sha 格式错、缺租户头等 | 400 |
| `QUOTA_EXCEEDED` | 分片过大、全仓容量不足 | 422 |
| `CHECKSUM_MISMATCH` | 分片或整对象 SHA-256 不一致 | 422 |
| `CONFLICT` | 同号分片内容冲突、对非 ACTIVE 会话操作、并发竞争失败 | 409 |
| `PARTS_INCOMPLETE` | complete 时分片未齐 | 409 |
| `STORAGE_FAULT` | 磁盘/IO/元数据故障、引用计数下溢、重启发现版本指向缺失 blob | 500 |

底层 `IOException` 等只作为 cause 进入服务端日志，响应体仅含
`{"errorType": ..., "message": ...}`；未预期异常兜底为 `STORAGE_FAULT`，
不向调用方透出堆栈或底层异常。

---

## 8. REST API

所有接口需带头 `X-Tenant-Id: <tenant>`。

| 方法与路径 | 说明 |
|---|---|
| `POST /api/objects/multipart` | body: `{objectKey,totalParts,contentSha256?,maxPartSize?}` → uploadId |
| `GET /api/objects/multipart/{uploadId}` | 查询会话状态与已收分片（支持续传前探测） |
| `PUT /api/objects/multipart/{uploadId}/parts/{n}` | 上传第 n 片；可选头 `X-Part-Sha256` |
| `POST /api/objects/multipart/{uploadId}/complete` | 完成；可选头 `X-Content-Sha256` |
| `DELETE /api/objects/multipart/{uploadId}` | 中止会话并释放在途容量 |
| `PUT /api/objects?objectKey=k` | 一次性写入；可选头 `X-Content-Sha256` |
| `GET /api/objects?objectKey=k&versionId?` | 下载（响应头带 `X-Version-Id`、`X-Content-Sha256`） |
| `GET /api/objects/versions?objectKey=k` | 列出该对象全部版本 |
| `GET /api/objects/list?prefix=` | 列举本租户对象 key |
| `DELETE /api/objects?objectKey=k&versionId?` | 带 versionId 删单版本；不带删全部版本 |
| `GET /api/objects/quota` | 容量使用情况 |

> 字节接口直接读取原始 `InputStream`，并通过
> `spring.mvc.formcontent.filter.enabled=false` 关闭表单内容过滤器，
> 因此 `curl --data-binary @file`（默认 `application/x-www-form-urlencoded`）
> 也能正确收到字节；推荐显式发送 `Content-Type: application/octet-stream`。

---

## 9. 验证方法

```bash
mvn test
```

测试（共 30 个，日志打印关键输入与判定依据，关键字 `[case-input]` / `[judge]`）：

| 测试类 | 覆盖点 |
|---|---|
| `MultipartResumeTest` | 乱序、重复分片幂等/冲突、跨重启续传、完成重试幂等、PARTS_INCOMPLETE、abort |
| `ChecksumFailureTest` | 分片级/对象级/simple-put 三层校验失败：拒绝原因、无半成品、不泄漏容量、可恢复 |
| `DedupTest` | 多版本/多对象/多写入方式共享 blob、refCount 准确、最后引用才回收 |
| `VersionConcurrencyTest` | 8 线程并发覆盖下读者只见自洽版本、并发删除与覆盖结果收敛、删版本作用域 |
| `QuotaTest` | 分片上限、全仓容量、去重零计费、失败零残留（临时文件/目录/容量） |
| `TenantIsolationTest` | 跨租户读/删/列举/会话操作统一 NOT_FOUND，响应不可区分、判定口径一致 |
| `RestartConsistencyTest` | 在途上传恢复、版本存活、refCount 重建、容量基线、孤儿目录清理 |
| `WebApiIntegrationTest` | HTTP 端到端流程与状态码映射 |

手工冒烟（启动后）：

```bash
# 发起
curl -s -XPOST localhost:8080/api/objects/multipart -H 'X-Tenant-Id: t1' \
  -H 'Content-Type: application/json' \
  -d '{"objectKey":"demo/file","totalParts":2}'
# 分片
printf 'hello-' | curl -s -XPUT 'localhost:8080/api/objects/multipart/<id>/parts/1' -H 'X-Tenant-Id: t1' --data-binary @-
printf 'world!' | curl -s -XPUT 'localhost:8080/api/objects/multipart/<id>/parts/2' -H 'X-Tenant-Id: t1' --data-binary @-
# 完成 / 读取
curl -s -XPOST 'localhost:8080/api/objects/multipart/<id>/complete' -H 'X-Tenant-Id: t1'
curl -s 'localhost:8080/api/objects?objectKey=demo/file' -H 'X-Tenant-Id: t1'
```
