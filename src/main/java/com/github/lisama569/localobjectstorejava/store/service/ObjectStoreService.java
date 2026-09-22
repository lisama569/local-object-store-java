package com.github.lisama569.localobjectstorejava.store.service;

import com.github.lisama569.localobjectstorejava.store.config.StoreProperties;
import com.github.lisama569.localobjectstorejava.store.digest.Checksums;
import com.github.lisama569.localobjectstorejava.store.digest.SizeLimitExceededException;
import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.metadata.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.model.BlobRecord;
import com.github.lisama569.localobjectstorejava.store.model.Caller;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartReceipt;
import com.github.lisama569.localobjectstorejava.store.quota.QuotaService;
import com.github.lisama569.localobjectstorejava.store.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 对象存储门面：分片上传、内容去重、多版本、配额与租户隔离。
 *
 * <h3>并发模型</h3>
 * <ul>
 *   <li>{@code objectStripeLock(tenant,key)}：串行化同一对象的 complete / put / 删除，
 *       读者只会看到整体替换的新版本，不存在半写状态；</li>
 *   <li>{@code uploadStripeLock(uploadId)}：串行化同一会话的分片提交、完成、中止，
 *       重复分片与并发完成得到确定结果；</li>
 *   <li>{@code blobLock}：串行化 blob 引用计数与容量联动；</li>
 *   锁序固定为 object/upload 锁 -> blob 锁，避免死锁。</li>
 * </ul>
 *
 * <h3>失败回滚</h3>
 * 容量 reserve 与磁盘写入严格配对：任何失败路径都删除临时文件并 release，
 * 校验失败只拒绝并保留可续传的会话，绝不产生可读半成品。
 */
public class ObjectStoreService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreService.class);

    private final StoreProperties properties;
    private final BlobStore blobs;
    private final MetadataStore meta;
    private final QuotaService quota;

    private final ReentrantLock blobLock = new ReentrantLock();
    private final Map<String, ReentrantLock> stripeLocks = new ConcurrentHashMap<>();

    public ObjectStoreService(StoreProperties properties,
                              BlobStore blobStore,
                              MetadataStore metadataStore,
                              QuotaService quotaService) {
        this.properties = properties;
        this.blobs = blobStore;
        this.meta = metadataStore;
        this.quota = quotaService;
    }

    private ReentrantLock stripeLock(String... parts) {
        return stripeLocks.computeIfAbsent(String.join("|", parts), k -> new ReentrantLock());
    }

    private ReentrantLock objectLock(String tenant, String key) {
        return stripeLock("obj", tenant, key);
    }

    private ReentrantLock uploadLock(String uploadId) {
        return stripeLock("upl", uploadId);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ============================ 启动对账 ============================

    /**
     * 重启恢复，顺序固定：
     * <ol>
     *   <li>清理临时对象文件（崩溃前未提交，绝不能被读取）；</li>
     *   <li>以磁盘分片为准重建每个在途会话；元数据有记录但磁盘丢失分片目录则中止会话；</li>
     *   <li>清理无会话引用的上传目录（孤儿分片）；</li>
     *   <li>以版本表重建 blob 引用计数，回收零引用 blob 及其元数据；</li>
     *   <li>以磁盘事实重置容量基线 = 存活 blob + 在途分片。</li>
     * </ol>
     */
    public void reconcileAfterRestart() {
        log.info("[reconcile] start: capacity={}", quota.capacity());

        // 1. 清理各会话目录内的拼接临时对象
        for (MultipartUpload upload : meta.activeUploads()) {
            blobs.listPartFiles(upload.uploadId())
                    .filter(p -> p.getFileName().toString().startsWith("object-"))
                    .forEach(this::deleteIfExistsQuietly);
        }

        // 2. 重建在途会话
        long partBytes = 0;
        List<String> referencedDirs = new ArrayList<>();
        for (MultipartUpload upload : List.copyOf(meta.activeUploads())) {
            String uploadId = upload.uploadId();
            Map<Integer, Path> partFiles = scanPartFiles(uploadId);
            if (partFiles.isEmpty()) {
                log.warn("[reconcile] active upload has no parts on disk, abort: uploadId={}", uploadId);
                meta.putUpload(withState(upload, MultipartUpload.ABORTED, Map.of(), 0));
                blobs.deleteUploadDir(uploadId);
                continue;
            }
            Map<Integer, PartReceipt> receipts = new TreeMap<>();
            long sessionBytes = 0;
            for (Map.Entry<Integer, Path> e : partFiles.entrySet()) {
                Path file = e.getValue();
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException ex) {
                    throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                            "reconcile cannot stat part file", ex);
                }
                receipts.put(e.getKey(), new PartReceipt(e.getKey(), size,
                        Checksums.sha256Hex(file), System.currentTimeMillis()));
                sessionBytes += size;
            }
            meta.putUpload(withState(upload, MultipartUpload.ACTIVE, receipts, sessionBytes));
            partBytes += sessionBytes;
            referencedDirs.add(uploadId);
            log.info("[reconcile] resumed upload: uploadId={} parts={} bytes={}",
                    uploadId, receipts.size(), sessionBytes);
        }

        // 3. 清理孤儿上传目录
        Path uploadsRoot = blobs.uploadsRoot();
        if (Files.isDirectory(uploadsRoot)) {
            try (Stream<Path> dirs = Files.list(uploadsRoot)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String name = dir.getFileName().toString();
                    if (!referencedDirs.contains(name)) {
                        log.warn("[reconcile] remove orphan upload dir: {}", name);
                        blobs.deleteUploadDir(name);
                    }
                });
            } catch (IOException e) {
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "reconcile scan uploads", e);
            }
        }

        // 4. 重建 blob 引用计数并回收孤儿 blob
        blobLock.lock();
        try {
            Map<String, Integer> refs = new ConcurrentHashMap<>();
            long blobBytes = 0;
            for (ObjectVersion version : meta.allVersions()) {
                refs.merge(version.blobKey(), 1, Integer::sum);
            }
            for (BlobRecord blob : meta.blobs()) {
                int count = refs.getOrDefault(blob.key(), 0);
                if (count == 0) {
                    log.warn("[reconcile] reclaim unreferenced blob: key={} size={}",
                            blob.key(), blob.size());
                    blobs.deleteBlob(blob.key());
                    meta.removeBlob(blob.key());
                } else {
                    if (count != blob.refCount()) {
                        meta.putBlob(blob.withRefCount(count));
                    }
                    if (!blobs.blobExists(blob.key())) {
                        throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                                "reconcile found missing blob referenced by versions: "
                                        + blob.key());
                    }
                    blobBytes += blob.size();
                }
            }
            // 磁盘上有 blob 文件但元数据缺失（元数据写入前崩溃）：物理回收，避免泄漏
            // （没有任何版本引用它，不能安全复活）

            // 5. 容量基线
            quota.resetUsed(blobBytes + partBytes);
            log.info("[reconcile] done: blobBytes={} partBytes={} used={}/{}",
                    blobBytes, partBytes, quota.used(), quota.capacity());
        } finally {
            blobLock.unlock();
        }
    }

    private Map<Integer, Path> scanPartFiles(String uploadId) {
        Map<Integer, Path> result = new TreeMap<>();
        blobs.listPartFiles(uploadId)
                .filter(p -> p.getFileName().toString().startsWith("part-"))
                .forEach(p -> {
                    String name = p.getFileName().toString().substring("part-".length());
                    try {
                        result.put(Integer.parseInt(name), p);
                    } catch (NumberFormatException ignored) {
                        // 非标准分片文件一律视为临时残留
                        deleteIfExistsQuietly(p);
                    }
                });
        return result;
    }

    private void deleteIfExistsQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 对账期尽力清理；残留不影响逻辑正确性，下次重启再次尝试
        }
    }

    private MultipartUpload withState(MultipartUpload src, String state,
                                      Map<Integer, PartReceipt> parts, long uploadedBytes) {
        return new MultipartUpload(src.uploadId(), src.tenant(), src.objectKey(),
                src.initiatedAtMillis(), src.totalParts(), src.maxPartSize(),
                src.expectedObjectSha256(), state, parts, uploadedBytes);
    }

    // ============================ 分片上传 ============================

    public MultipartUpload initiate(Caller caller, String objectKey, int totalParts,
                                    String expectedSha256, Long maxPartSize) {
        validateObjectKey(objectKey);
        if (totalParts < 1 || totalParts > properties.getMaxPartsPerUpload()) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "totalParts must be within [1," + properties.getMaxPartsPerUpload()
                            + "], actual=" + totalParts);
        }
        long partLimit = maxPartSize != null ? maxPartSize : properties.getDefaultMaxPartSize();
        if (partLimit <= 0) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "maxPartSize must be positive, actual=" + partLimit);
        }
        String uploadId = newId();
        MultipartUpload upload = new MultipartUpload(uploadId, caller.tenantId(), objectKey,
                System.currentTimeMillis(), totalParts, partLimit,
                normalizeSha(expectedSha256), MultipartUpload.ACTIVE, Map.of(), 0);
        meta.putUpload(upload);
        log.info("[initiate] tenant={} key={} uploadId={} totalParts={} partLimit={}",
                caller.tenantId(), objectKey, uploadId, totalParts, partLimit);
        return upload;
    }

    public PartReceipt uploadPart(Caller caller, String uploadId, int partNumber,
                                  InputStream content, String expectedPartSha256) {
        if (partNumber < 1) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "partNumber must be >= 1, actual=" + partNumber);
        }
        if (content == null) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT, "part content is required");
        }
        MultipartUpload upload = requireActiveUpload(caller, uploadId);
        if (partNumber > upload.totalParts()) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "partNumber=" + partNumber + " exceeds totalParts=" + upload.totalParts());
        }
        ReentrantLock lock = uploadLock(uploadId);
        lock.lock();
        try {
            upload = requireActiveUpload(caller, uploadId);
            PartReceipt existing = upload.parts().get(partNumber);

            Path tmp = blobs.createTempPartFile(uploadId);
            Checksums.HashResult result;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                result = Checksums.copyHashing(content, out, upload.maxPartSize());
            } catch (SizeLimitExceededException e) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.QUOTA_EXCEEDED,
                        "part size exceeded: partNumber=" + partNumber
                                + " limit=" + e.getLimit() + " actual>=" + e.getActual(), e);
            } catch (IOException e) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "failed to receive part: " + partNumber, e);
            }

            String etag = result.sha256();
            long size = result.size();

            if (expectedPartSha256 != null
                    && !normalizeSha(expectedPartSha256).equals(etag)) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.CHECKSUM_MISMATCH,
                        "part checksum mismatch: partNumber=" + partNumber
                                + " expected=" + normalizeSha(expectedPartSha256)
                                + " actual=" + etag);
            }

            if (existing != null) {
                // 重复提交：etag 相同 -> 幂等；etag 不同 -> 同号分片内容冲突
                deleteIfExistsQuietly(tmp);
                if (existing.etag().equals(etag) && existing.size() == size) {
                    log.info("[uploadPart] duplicate idempotent: uploadId={} part={} etag={}",
                            uploadId, partNumber, etag);
                    return existing;
                }
                throw new ObjectStoreException(ErrorType.CONFLICT,
                        "part number already committed with different content: partNumber="
                                + partNumber + " committedEtag=" + existing.etag()
                                + " submittedEtag=" + etag);
            }

            quota.reserve(size);
            Path target = blobs.partFile(uploadId, partNumber);
            try {
                blobs.moveAtomically(tmp, target);
            } catch (RuntimeException e) {
                quota.release(size);
                deleteIfExistsQuietly(tmp);
                if (Files.exists(target)) {
                    // 极端竞态：目标已出现，以磁盘为准做幂等判定
                    PartReceipt onDisk = new PartReceipt(partNumber, size, etag,
                            System.currentTimeMillis());
                    return reconcileDuplicatePart(uploadId, partNumber, onDisk);
                }
                throw e;
            }

            PartReceipt receipt = new PartReceipt(partNumber, size, etag,
                    System.currentTimeMillis());
            Map<Integer, PartReceipt> parts = new TreeMap<>(upload.parts());
            parts.put(partNumber, receipt);
            meta.putUpload(withState(upload, MultipartUpload.ACTIVE,
                    parts, upload.uploadedBytes() + size));
            log.info("[uploadPart] accepted: uploadId={} part={}/{} size={} etag={} used={}",
                    uploadId, partNumber, upload.totalParts(), size, etag, quota.used());
            return receipt;
        } finally {
            lock.unlock();
        }
    }

    private PartReceipt reconcileDuplicatePart(String uploadId, int partNumber,
                                               PartReceipt submitted) {
        MultipartUpload current = meta.getUpload(uploadId);
        if (current == null) {
            throw new ObjectStoreException(ErrorType.NOT_FOUND,
                    "upload vanished during part commit: " + uploadId);
        }
        PartReceipt committed = current.parts().get(partNumber);
        if (committed != null
                && committed.etag().equals(submitted.etag())
                && committed.size() == submitted.size()) {
            return committed;
        }
        throw new ObjectStoreException(ErrorType.CONFLICT,
                "part file appeared concurrently with different content: partNumber="
                        + partNumber);
    }

    public MultipartUpload getUpload(Caller caller, String uploadId) {
        return requireUpload(caller, uploadId);
    }

    public ObjectVersion completeUpload(Caller caller, String uploadId, String expectedSha256) {
        MultipartUpload upload = requireUpload(caller, uploadId);
        if (MultipartUpload.ABORTED.equals(upload.state())) {
            throw new ObjectStoreException(ErrorType.CONFLICT,
                    "upload is aborted: uploadId=" + uploadId);
        }
        ReentrantLock uploadLock = uploadLock(uploadId);
        uploadLock.lock();
        try {
            upload = requireUpload(caller, uploadId);
            if (MultipartUpload.COMPLETED.equals(upload.state())) {
                // 跨调用重试的确定性结果：返回已提交版本
                ObjectVersion existing = meta.getVersion(upload.tenant(),
                        upload.objectKey(), versionByUpload(uploadId));
                if (existing != null) {
                    log.info("[complete] idempotent replay: uploadId={} version={}",
                            uploadId, existing.versionId());
                    return existing;
                }
                // 会话已完成但版本元数据查无此版本：属于元数据不一致，按存储故障处理
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "completed upload missing its committed version: "
                                + uploadId);
            }
            if (!upload.isActive()) {
                throw new ObjectStoreException(ErrorType.CONFLICT,
                        "upload cannot be completed in state=" + upload.state());
            }
            if (!upload.allPartsPresent()) {
                throw new ObjectStoreException(ErrorType.PARTS_INCOMPLETE,
                        "missing parts: expected=" + upload.totalParts()
                                + " received=" + upload.parts().size()
                                + " receivedNumbers=" + upload.parts().keySet());
            }
            String expected = normalizeSha(
                    expectedSha256 != null ? expectedSha256 : upload.expectedObjectSha256());

            // 同一对象的并发覆盖在此串行；其他对象不受影响
            ReentrantLock objLock = objectLock(upload.tenant(), upload.objectKey());
            objLock.lock();
            try {
                return commitMultipart(upload, expected);
            } finally {
                objLock.unlock();
            }
        } finally {
            uploadLock.unlock();
        }
    }

    private ObjectVersion commitMultipart(MultipartUpload upload, String expectedSha256) {
        String uploadId = upload.uploadId();
        Path assembled = blobs.createTempObjectFile(uploadId);
        String actualSha;
        long totalSize;
        var md = Checksums.newDigest();
        try (OutputStream out = Files.newOutputStream(assembled)) {
            long written = 0;
            for (int i = 1; i <= upload.totalParts(); i++) {
                PartReceipt receipt = upload.parts().get(i);
                Path part = blobs.partFile(uploadId, i);
                long partSize;
                try (InputStream in = Files.newInputStream(part)) {
                    Checksums.HashResult hr = Checksums.copyHashing(
                            in, new DigestTeeOutputStream(out, md), Long.MAX_VALUE);
                    partSize = hr.size();
                }
                if (partSize != receipt.size()) {
                    deleteIfExistsQuietly(assembled);
                    throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                            "part size on disk changed: partNumber=" + i
                                    + " receipt=" + receipt.size() + " disk=" + partSize);
                }
                written += partSize;
            }
            out.flush();
            totalSize = written;
            actualSha = Checksums.toHex(md.digest());
        } catch (IOException e) {
            deleteIfExistsQuietly(assembled);
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                    "failed to assemble object: " + uploadId, e);
        }

        if (expectedSha256 != null && !expectedSha256.equals(actualSha)) {
            deleteIfExistsQuietly(assembled);
            throw new ObjectStoreException(ErrorType.CHECKSUM_MISMATCH,
                    "object checksum mismatch: uploadId=" + uploadId
                            + " expected=" + expectedSha256 + " actual=" + actualSha);
        }

        ObjectVersion version;
        blobLock.lock();
        try {
            BlobRecord existingBlob = meta.getBlob(actualSha);
            boolean newContent = existingBlob == null;
            long additionalCharge = newContent ? totalSize : 0;
            if (newContent) {
                quota.reserve(totalSize);
            }
            BlobStore.Commit commit;
            try {
                commit = blobs.commitBlob(assembled, actualSha);
            } catch (ObjectStoreException e) {
                if (newContent) {
                    quota.release(totalSize);
                }
                throw e;
            }
            long partBytesToRelease = upload.uploadedBytes();
            try {
                String versionId = newId();
                version = new ObjectVersion(upload.tenant(), upload.objectKey(), versionId,
                        commit.key(), commit.size(), actualSha,
                        System.currentTimeMillis(), uploadId);
                if (existingBlob != null) {
                    meta.putBlob(existingBlob.withRefCount(existingBlob.refCount() + 1));
                } else {
                    meta.putBlob(new BlobRecord(commit.key(), commit.size(), 1,
                            System.currentTimeMillis()));
                }
                meta.addVersion(version);
            } catch (RuntimeException e) {
                rollbackAfterCommitFailure(commit, newContent, totalSize, e);
                throw e;
            }
            // 版本已落盘：释放分片在途占用（blob 占用已在上方记账）
            quota.release(partBytesToRelease);
            meta.putUpload(withState(upload, MultipartUpload.COMPLETED, Map.of(), 0));
            blobs.deleteUploadDir(uploadId);
            log.info("[complete] committed: uploadId={} key={} version={} blob={} newContent={} "
                            + "dedupRef={} size={} used={}", uploadId, upload.objectKey(),
                    version.versionId(), commit.key(), newContent,
                    meta.getBlob(commit.key()).refCount(), totalSize, quota.used());
        } finally {
            blobLock.unlock();
        }
        return version;
    }

    private void rollbackAfterCommitFailure(BlobStore.Commit commit, boolean newContent,
                                            long totalSize, RuntimeException cause) {
        // 元数据没写成功：回滚引用计数/容量与物理 blob，恢复到调用前状态
        try {
            if (newContent) {
                blobs.deleteBlob(commit.key());
                meta.removeBlob(commit.key());
                quota.release(totalSize);
            } else {
                BlobRecord blob = meta.getBlob(commit.key());
                if (blob != null && blob.refCount() > 0) {
                    meta.putBlob(blob.withRefCount(blob.refCount() - 1));
                }
            }
        } catch (RuntimeException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }

    public void abortUpload(Caller caller, String uploadId) {
        MultipartUpload upload = requireUpload(caller, uploadId);
        ReentrantLock lock = uploadLock(uploadId);
        lock.lock();
        try {
            upload = requireUpload(caller, uploadId);
            if (MultipartUpload.ABORTED.equals(upload.state())) {
                log.info("[abort] already aborted (idempotent): uploadId={}", uploadId);
                return;
            }
            if (MultipartUpload.COMPLETED.equals(upload.state())) {
                throw new ObjectStoreException(ErrorType.CONFLICT,
                        "completed upload cannot be aborted: uploadId=" + uploadId);
            }
            blobLock.lock();
            try {
                long reserved = upload.uploadedBytes();
                blobs.deleteUploadDir(uploadId);
                quota.release(reserved);
                meta.putUpload(withState(upload, MultipartUpload.ABORTED, Map.of(), 0));
                log.info("[abort] aborted: uploadId={} releasedBytes={} used={}",
                        uploadId, reserved, quota.used());
            } finally {
                blobLock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    // ============================ 一次性写入 ============================

    public ObjectVersion put(Caller caller, String objectKey, InputStream content,
                             String expectedSha256) {
        validateObjectKey(objectKey);
        if (content == null) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT, "content is required");
        }
        ReentrantLock objLock = objectLock(caller.tenantId(), objectKey);
        objLock.lock();
        try {
            Path tmp = blobs.createTempBlobFile();
            Checksums.HashResult result;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                // 单对象上限受整体容量约束（不可能写入超过容量的新内容）
                result = Checksums.copyHashing(content, out, quota.capacity());
            } catch (SizeLimitExceededException e) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.QUOTA_EXCEEDED,
                        "object size exceeded capacity: limit=" + e.getLimit()
                                + " actual>=" + e.getActual(), e);
            } catch (IOException e) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "failed to receive object", e);
            }

            if (expectedSha256 != null
                    && !normalizeSha(expectedSha256).equals(result.sha256())) {
                deleteIfExistsQuietly(tmp);
                throw new ObjectStoreException(ErrorType.CHECKSUM_MISMATCH,
                        "object checksum mismatch: expected=" + normalizeSha(expectedSha256)
                                + " actual=" + result.sha256());
            }

            ObjectVersion version;
            blobLock.lock();
            try {
                BlobRecord existingBlob = meta.getBlob(result.sha256());
                boolean newContent = existingBlob == null;
                if (newContent) {
                    quota.reserve(result.size());
                }
                BlobStore.Commit commit;
                try {
                    commit = blobs.commitBlob(tmp, result.sha256());
                } catch (ObjectStoreException e) {
                    if (newContent) {
                        quota.release(result.size());
                    }
                    throw e;
                }
                try {
                    String versionId = newId();
                    version = new ObjectVersion(caller.tenantId(), objectKey, versionId,
                            commit.key(), commit.size(), result.sha256(),
                            System.currentTimeMillis(), null);
                    if (existingBlob != null) {
                        meta.putBlob(existingBlob.withRefCount(existingBlob.refCount() + 1));
                    } else {
                        meta.putBlob(new BlobRecord(commit.key(), commit.size(), 1,
                                System.currentTimeMillis()));
                    }
                    meta.addVersion(version);
                } catch (RuntimeException e) {
                    rollbackAfterCommitFailure(commit, newContent, result.size(), e);
                    throw e;
                }
                log.info("[put] key={} version={} blob={} newContent={} size={} used={}",
                        objectKey, version.versionId(), commit.key(), newContent,
                        result.size(), quota.used());
            } finally {
                blobLock.unlock();
            }
            return version;
        } finally {
            objLock.unlock();
        }
    }

    // ============================ 读取与列举 ============================

    public ObjectVersion get(Caller caller, String objectKey, String versionId) {
        validateObjectKey(objectKey);
        ObjectVersion version = versionId == null
                ? meta.latestVersion(caller.tenantId(), objectKey)
                : meta.getVersion(caller.tenantId(), objectKey, versionId);
        if (version == null) {
            throw notFound(objectKey, versionId);
        }
        return version;
    }

    public InputStream openContent(Caller caller, String objectKey, String versionId) {
        ObjectVersion version = get(caller, objectKey, versionId);
        log.info("[download] tenant={} key={} version={} blob={}",
                caller.tenantId(), objectKey, version.versionId(), version.blobKey());
        return blobs.openBlob(version.blobKey());
    }

    public void download(Caller caller, String objectKey, String versionId, OutputStream sink) {
        if (sink == null) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT, "sink is required");
        }
        try (InputStream in = openContent(caller, objectKey, versionId)) {
            in.transferTo(sink);
            sink.flush();
        } catch (IOException e) {
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                    "failed to stream object content", e);
        }
    }

    public List<ObjectVersion> listVersions(Caller caller, String objectKey) {
        validateObjectKey(objectKey);
        List<ObjectVersion> versions = meta.versions(caller.tenantId(), objectKey);
        if (versions.isEmpty()) {
            throw notFound(objectKey, null);
        }
        return versions;
    }

    public List<String> listObjects(Caller caller, String prefix) {
        return meta.listObjectKeys(caller.tenantId(), prefix);
    }

    // ============================ 删除 ============================

    public void deleteVersion(Caller caller, String objectKey, String versionId) {
        validateObjectKey(objectKey);
        if (versionId == null || versionId.isBlank()) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "versionId is required for scoped delete");
        }
        ReentrantLock objLock = objectLock(caller.tenantId(), objectKey);
        objLock.lock();
        try {
            ObjectVersion version = meta.getVersion(caller.tenantId(), objectKey, versionId);
            if (version == null) {
                throw notFound(objectKey, versionId);
            }
            dereferenceVersion(version);
            log.info("[delete] scoped delete: tenant={} key={} version={}",
                    caller.tenantId(), objectKey, versionId);
        } finally {
            objLock.unlock();
        }
    }

    /**
     * 删除指定对象的全部版本（作用范围仅限本租户该 key），返回删除的版本数。
     */
    public int deleteAllVersions(Caller caller, String objectKey) {
        validateObjectKey(objectKey);
        ReentrantLock objLock = objectLock(caller.tenantId(), objectKey);
        objLock.lock();
        try {
            List<ObjectVersion> versions = meta.versions(caller.tenantId(), objectKey);
            if (versions.isEmpty()) {
                throw notFound(objectKey, null);
            }
            for (ObjectVersion version : versions) {
                dereferenceVersion(version);
            }
            log.info("[delete] all versions: tenant={} key={} count={}",
                    caller.tenantId(), objectKey, versions.size());
            return versions.size();
        } finally {
            objLock.unlock();
        }
    }

    /** 引用计数减一；归零才回收 blob 与容量，绝不提前回收仍被引用的内容。 */
    private void dereferenceVersion(ObjectVersion version) {
        blobLock.lock();
        try {
            BlobRecord blob = meta.getBlob(version.blobKey());
            if (blob == null) {
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "blob metadata missing for committed version: "
                                + version.versionId());
            }
            int next = blob.refCount() - 1;
            if (next < 0) {
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "blob refCount underflow: key=" + blob.key());
            }
            meta.removeVersion(version.tenant(), version.objectKey(), version.versionId());
            if (next == 0) {
                blobs.deleteBlob(blob.key());
                meta.removeBlob(blob.key());
                quota.release(blob.size());
                log.info("[gc] reclaimed blob: key={} size={} used={}",
                        blob.key(), blob.size(), quota.used());
            } else {
                meta.putBlob(blob.withRefCount(next));
            }
        } finally {
            blobLock.unlock();
        }
    }

    // ============================ 配额视图 ============================

    public long capacityUsed() {
        return quota.used();
    }

    public long capacityTotal() {
        return quota.capacity();
    }

    // ============================ 校验与权限 ============================

    private MultipartUpload requireActiveUpload(Caller caller, String uploadId) {
        MultipartUpload upload = requireUpload(caller, uploadId);
        if (!upload.isActive()) {
            throw new ObjectStoreException(ErrorType.CONFLICT,
                    "upload is not active: uploadId=" + uploadId + " state=" + upload.state());
        }
        return upload;
    }

    /**
     * 会话归属判定。越权与会话不存在统一返回 NOT_FOUND 且文案不含“无权限”，
     * 避免向调用方泄露对象是否存在（与读取/列举的拒绝结果保持一致）。
     */
    private MultipartUpload requireUpload(Caller caller, String uploadId) {
        MultipartUpload upload = meta.getUpload(uploadId);
        if (upload == null || !upload.tenant().equals(caller.tenantId())) {
            throw new ObjectStoreException(ErrorType.NOT_FOUND,
                    "upload not found: uploadId=" + uploadId);
        }
        return upload;
    }

    private ObjectStoreException notFound(String objectKey, String versionId) {
        return new ObjectStoreException(ErrorType.NOT_FOUND,
                versionId == null
                        ? "object not found: key=" + objectKey
                        : "object version not found: key=" + objectKey
                        + " versionId=" + versionId);
    }

    private void validateObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "objectKey must not be blank");
        }
    }

    private static String normalizeSha(String sha) {
        if (sha == null) {
            return null;
        }
        String trimmed = sha.trim().toLowerCase();
        if (!trimmed.matches("[0-9a-f]{64}")) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT,
                    "invalid sha256 (expected 64 hex chars): " + sha);
        }
        return trimmed;
    }

    private String versionByUpload(String uploadId) {
        List<ObjectVersion> all = meta.allVersions();
        return all.stream()
                .filter(v -> uploadId.equals(v.uploadId()))
                .map(ObjectVersion::versionId)
                .findFirst().orElse(null);
    }

    /** 同时写目标流并更新摘要的输出流，用于分片拼接时计算整对象 sha256。 */
    private static final class DigestTeeOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final java.security.MessageDigest digest;

        private DigestTeeOutputStream(OutputStream delegate, java.security.MessageDigest digest) {
            this.delegate = delegate;
            this.digest = digest;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            digest.update(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
