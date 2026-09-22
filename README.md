# local-object-store-java

基于本地磁盘与内存元数据模拟的对象仓库，支持分片上传、内容去重、版本管理、
配额约束与按调用方隔离的访问控制。所有并发与重启场景下的结果一致且可解释。

## 运行与验证

```bash
mvn test          # 运行全部 32 个测试（单测日志打印关键输入与判定依据）
mvn spring-boot:run # 启动 REST 服务（默认 8080 端口）
```

REST 接口统一以 `X-Owner` 请求头标识调用方，主要端点：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads` | 创建分片上传会话 |
| GET | `/api/uploads/{uploadId}` | 查询会话与已提交分片（续传入口） |
| PUT | `/api/uploads/{uploadId}/parts/{n}` | 提交分片，可带 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/complete` | 完成上传，可带整体 `X-Expected-Sha256` |
| POST | `/api/uploads/{uploadId}/abort` | 中止并回收分片 |
| PUT | `/api/objects/{key}` | 整体写入 |
| GET | `/api/objects/{key}[?versionId=]` | 读取当前/指定版本 |
| GET | `/api/objects/{key}/versions` | 版本历史 |
| GET | `/api/objects` | 列举调用方可见的键 |
| DELETE | `/api/objects/{key}[?versionId=]` | 删除当前版本（标记）或指定版本（物理） |
| GET | `/api/usage` | 查询调用方配额用量 |

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

## 配额策略

- `objectstore.quota-bytes`：每个调用方的逻辑字节配额，统计口径为
  「存活版本字节 + 进行中上传的分片字节」。完成上传时引用转移，口径不变；
  中止或物理删除立即释放。
- `objectstore.max-part-size`：单分片字节上限，超限返回 `PART_SIZE_EXCEEDED`。
- `objectstore.max-parts`：分片序号上限。
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
| `BAD_REQUEST` | 400 | 参数非法 |
| `CHECKSUM_MISMATCH` | 422 | 分片/整体校验和不一致 |
| `QUOTA_EXCEEDED` / `PART_SIZE_EXCEEDED` | 413 | 配额/分片大小超限 |
| `CONFLICT` | 409 | 同序号分片内容冲突 |
| `INVALID_STATE` | 409 | 会话已中止/已完成等非法状态 |
| `STORAGE_FAILURE` | 500 | 底层存储失败（含内容损坏、丢失） |

## 测试覆盖

`src/test/java/.../tests/` 下 32 个测试，日志打印关键输入与判定依据：

- `MultipartUploadTest`：乱序分片、重复分片（幂等/冲突）、跨会话续传、
  分片与整体校验失败、完成幂等、中止释放配额。
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
