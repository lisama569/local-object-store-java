package com.github.lisama569.localobjectstorejava.store;

import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartInfo;
import com.github.lisama569.localobjectstorejava.store.model.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 对象存储主流程：分片上传、去重、版本、配额与访问控制。
 * 所有复合操作经 {@link MetadataStore#inLock} 串行化，保证并发结果一致。
 * 越权访问一律返回 NOT_FOUND，不泄露对象是否存在。
 */
@Service
public class ObjectStoreService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreService.class);

    private final StorageConfig config;
    private final ContentStore contentStore;
    private final MetadataStore metadataStore;
    /** 可替换时钟，测试中用来推进空闲时间；生产环境为系统时钟。 */
    private final LongSupplier clock;

    @Autowired
    public ObjectStoreService(StorageConfig config, ContentStore contentStore, MetadataStore metadataStore) {
        this(config, contentStore, metadataStore, System::currentTimeMillis);
    }

    public ObjectStoreService(StorageConfig config, ContentStore contentStore, MetadataStore metadataStore,
                              LongSupplier clock) {
        this.config = config;
        this.contentStore = contentStore;
        this.metadataStore = metadataStore;
        this.clock = clock;
    }

    /** 创建分片上传会话（不声明分片总数，老流程）。 */
    public MultipartUpload createUpload(String owner, String key) {
        return createUpload(owner, key, null);
    }

    /** 创建分片上传会话；expectedParts 非空时声明总分片数，完成时必须 1..N 到齐。 */
    public MultipartUpload createUpload(String owner, String key, Integer expectedParts) {
        requireText(owner, "owner");
        requireText(key, "key");
        if (expectedParts != null && (expectedParts < 1 || expectedParts > config.getMaxParts())) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "声明的分片总数越界: " + expectedParts + " (允许 1.." + config.getMaxParts() + ")");
        }
        long now = clock.getAsLong();
        MultipartUpload upload = new MultipartUpload(
                UUID.randomUUID().toString(), owner, key, now, now + config.getUploadTtlMillis(), expectedParts);
        metadataStore.saveUpload(upload);
        log.info("createUpload owner={} key={} uploadId={} expectedParts={}",
                owner, key, upload.uploadId(), expectedParts);
        return upload;
    }

    /** 查询上传会话（含已提交分片），越权与不存在均返回 NOT_FOUND；查询会顺延过期时间。 */
    public MultipartUpload getUpload(String owner, String uploadId) {
        return metadataStore.inLock(() -> {
            // 进锁先清扫：过期判定只使用“拿锁时刻”的时间，持锁段内不再读时钟，
            // 与后台清扫/并发续传的结果在这把锁上唯一确定。
            sweepExpired(owner);
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.EXPIRED) {
                throw expiredException(upload);
            }
            if (upload.state() == MultipartUpload.State.ACTIVE) {
                touch(upload);
                metadataStore.saveUpload(upload);
            }
            return upload;
        });
    }

    /**
     * 查看会话状态：做权限判定与过期清扫，但不顺延过期时间（区别于 {@link #getUpload}）。
     * 供运维/测试观察真实空闲状态使用。
     */
    public MultipartUpload.State peekUploadState(String owner, String uploadId) {
        return metadataStore.inLock(() -> {
            sweepExpired(owner);
            MultipartUpload upload = requireUpload(owner, uploadId);
            return upload.state();
        });
    }

    /** 提交一个分片；重复提交相同内容幂等成功，不同内容冲突。 */
    public PartInfo uploadPart(String owner, String uploadId, int partNumber,
                               byte[] data, String expectedSha256) {
        if (data == null) {
            throw new StoreException(StoreException.Code.BAD_REQUEST, "分片内容不能为空");
        }
        if (partNumber < 1 || partNumber > config.getMaxParts()) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "分片序号越界: " + partNumber + " (允许 1.." + config.getMaxParts() + ")");
        }
        if (data.length > config.getMaxPartSize()) {
            throw new StoreException(StoreException.Code.PART_SIZE_EXCEEDED,
                    "分片大小超限: " + data.length + " > " + config.getMaxPartSize());
        }
        String actualSha = ContentStore.sha256Hex(data);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha)) {
            log.info("uploadPart checksum mismatch uploadId={} part={} expected={} actual={}",
                    uploadId, partNumber, expectedSha256, actualSha);
            throw new StoreException(StoreException.Code.CHECKSUM_MISMATCH,
                    "分片校验和不一致: expected=" + expectedSha256 + " actual=" + actualSha);
        }
        return metadataStore.inLock(() -> {
            // 进锁先清扫本调用方的空闲会话；之后持锁段内不再做任何过期判定，
            // 保证本请求“成功续上/明确过期”二选一，不会把内容写到 EXPIRED 会话上。
            sweepExpired(owner);
            MultipartUpload upload = requireUpload(owner, uploadId);
            requireUsable(upload);
            if (upload.expectedParts() != null && partNumber > upload.expectedParts()) {
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "分片序号超过声明总数: part=" + partNumber
                                + " expectedParts=" + upload.expectedParts());
            }
            PartInfo existing = upload.parts().get(partNumber);
            if (existing != null) {
                if (existing.sha256().equals(actualSha)) {
                    log.info("uploadPart idempotent hit uploadId={} part={} sha={}",
                            uploadId, partNumber, actualSha);
                    touch(upload);
                    metadataStore.saveUpload(upload);
                    return existing; // 幂等成功，不重复计入
                }
                throw new StoreException(StoreException.Code.CONFLICT,
                        "分片 " + partNumber + " 已存在且内容不同: existing=" + existing.sha256()
                                + " incoming=" + actualSha);
            }
            checkQuotaNoSweep(owner, data.length);
            String sha = contentStore.put(data);
            PartInfo info = new PartInfo(partNumber, sha, data.length);
            upload.parts().put(partNumber, info);
            touch(upload);
            metadataStore.saveUpload(upload);
            log.info("uploadPart stored uploadId={} part={} size={} sha={}",
                    uploadId, partNumber, data.length, sha);
            return info;
        });
    }

    /** 完成上传：按分片序号拼接、整体校验后生成新版本；重复完成返回同一版本。 */
    public ObjectVersion completeUpload(String owner, String uploadId, String expectedContentSha256) {
        return metadataStore.inLock(() -> {
            sweepExpired(owner);
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.COMPLETED) {
                // 幂等：返回首次完成产生的版本
                return findVersion(owner, upload.key(), upload.completedVersionId());
            }
            requireUsable(upload);
            Integer expectedParts = upload.expectedParts();
            if (expectedParts != null) {
                // 声明了分片总数：必须 1..N 全部到齐，缺口明确返回 MISSING_PARTS
                List<Integer> missing = new ArrayList<>();
                for (int n = 1; n <= expectedParts; n++) {
                    if (!upload.parts().containsKey(n)) {
                        missing.add(n);
                    }
                }
                if (!missing.isEmpty()) {
                    log.info("completeUpload missing parts uploadId={} expected={} have={} missing={}",
                            uploadId, expectedParts, upload.parts().keySet(), missing);
                    throw new StoreException(StoreException.Code.MISSING_PARTS,
                            "分片未到齐: expectedParts=" + expectedParts + " missing=" + missing,
                            missing);
                }
            } else if (upload.parts().isEmpty()) {
                throw new StoreException(StoreException.Code.INVALID_STATE,
                        "上传会话没有任何分片，无法完成: " + uploadId);
            }
            byte[] assembled = assemble(upload);
            String contentHash = ContentStore.sha256Hex(assembled);
            if (expectedContentSha256 != null && !expectedContentSha256.equalsIgnoreCase(contentHash)) {
                log.info("completeUpload checksum mismatch uploadId={} expected={} actual={}",
                        uploadId, expectedContentSha256, contentHash);
                throw new StoreException(StoreException.Code.CHECKSUM_MISMATCH,
                        "整体校验和不一致: expected=" + expectedContentSha256 + " actual=" + contentHash);
            }
            List<String> partHashes = upload.parts().values().stream().map(PartInfo::sha256).toList();
            ObjectVersion version = new ObjectVersion(
                    UUID.randomUUID().toString(), owner, partHashes, contentHash,
                    assembled.length, System.currentTimeMillis(), false);
            StoredObject object = metadataStore.findObject(owner, upload.key())
                    .orElseGet(() -> new StoredObject(upload.key(), owner));
            object.versions().add(version);
            metadataStore.saveObject(object);
            // 分片引用由上传会话转移给新版本，会话不再持有
            upload.state(MultipartUpload.State.COMPLETED);
            upload.completedVersionId(version.versionId());
            metadataStore.saveUpload(upload);
            log.info("completeUpload owner={} key={} versionId={} size={} contentHash={}",
                    owner, upload.key(), version.versionId(), version.size(), contentHash);
            return version;
        });
    }

    /** 中止上传并回收分片占用。 */
    public void abortUpload(String owner, String uploadId) {
        metadataStore.inLock(() -> {
            sweepExpired(owner);
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.COMPLETED) {
                throw new StoreException(StoreException.Code.INVALID_STATE,
                        "上传已完成，不能中止: " + uploadId);
            }
            if (upload.state() == MultipartUpload.State.ACTIVE) {
                for (PartInfo part : upload.parts().values()) {
                    contentStore.release(part.sha256());
                }
                upload.state(MultipartUpload.State.ABORTED);
                metadataStore.saveUpload(upload);
            }
            log.info("abortUpload owner={} uploadId={}", owner, uploadId);
            return null;
        });
    }

    /** 简单整体写入（单分片语义）。 */
    public ObjectVersion putObject(String owner, String key, byte[] data, String expectedSha256) {
        requireText(owner, "owner");
        requireText(key, "key");
        if (data == null) {
            throw new StoreException(StoreException.Code.BAD_REQUEST, "内容不能为空");
        }
        String actualSha = ContentStore.sha256Hex(data);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha)) {
            throw new StoreException(StoreException.Code.CHECKSUM_MISMATCH,
                    "内容校验和不一致: expected=" + expectedSha256 + " actual=" + actualSha);
        }
        return metadataStore.inLock(() -> {
            sweepExpired(owner);
            checkQuotaNoSweep(owner, data.length);
            String sha = contentStore.put(data);
            ObjectVersion version = new ObjectVersion(
                    UUID.randomUUID().toString(), owner, List.of(sha), actualSha,
                    data.length, System.currentTimeMillis(), false);
            StoredObject object = metadataStore.findObject(owner, key)
                    .orElseGet(() -> new StoredObject(key, owner));
            object.versions().add(version);
            metadataStore.saveObject(object);
            log.info("putObject owner={} key={} versionId={} size={} sha={}",
                    owner, key, version.versionId(), data.length, actualSha);
            return version;
        });
    }

    /** 读取当前版本内容，读取时重新校验整体哈希。 */
    public byte[] getObject(String owner, String key) {
        StoredObject object = requireObject(owner, key);
        ObjectVersion current = object.current();
        if (current == null || current.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND, "对象不存在: " + key);
        }
        return readVersion(current);
    }

    /** 读取指定版本内容。 */
    public byte[] getObjectVersion(String owner, String key, String versionId) {
        ObjectVersion version = findVersion(owner, key, versionId);
        if (version.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND,
                    "版本是删除标记，无内容: " + versionId);
        }
        return readVersion(version);
    }

    /** 按字节区间读取当前版本：[start,end] 双闭区间，end 可传 -1 表示到对象末尾。 */
    public RangeRead getObjectRange(String owner, String key, long start, long end) {
        StoredObject object = requireObject(owner, key);
        ObjectVersion current = object.current();
        if (current == null || current.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND, "对象不存在: " + key);
        }
        return readRange(current, start, end);
    }

    /** 按字节区间读取指定版本。 */
    public RangeRead getObjectVersionRange(String owner, String key, String versionId, long start, long end) {
        ObjectVersion version = findVersion(owner, key, versionId);
        if (version.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND,
                    "版本是删除标记，无内容: " + versionId);
        }
        return readRange(version, start, end);
    }

    /** 一次区间读取的结果：切片内容 + 对象总长度 + 实际生效的闭区间边界。 */
    public record RangeRead(byte[] data, long totalSize, long start, long end) {}

    private RangeRead readRange(ObjectVersion version, long start, long endInclusive) {
        // 先整段读出并校验哈希，再切片：区间读取不能绕过内容校验
        byte[] full = readVersion(version);
        long total = full.length;
        if (start < 0 || endInclusive < -1 || (endInclusive >= 0 && endInclusive < start)) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "区间非法: start=" + start + " end=" + endInclusive
                            + "（要求 start>=0、end=-1 或 end>=start）");
        }
        long end = endInclusive == -1 ? total - 1 : endInclusive;
        if (start >= total || end >= total) {
            throw new StoreException(StoreException.Code.RANGE_NOT_SATISFIABLE,
                    "区间越界: start=" + start + " end=" + end + " objectSize=" + total,
                    total);
        }
        int len = (int) (end - start + 1);
        byte[] slice = new byte[len];
        System.arraycopy(full, (int) start, slice, 0, len);
        log.info("readRange versionId={} total={} range=[{},{}] sliceBytes={}",
                version.versionId(), total, start, end, len);
        return new RangeRead(slice, total, start, end);
    }

    /** 列出版本历史（元数据查询，权限判定与下载一致）。 */
    public List<ObjectVersion> listVersions(String owner, String key) {
        return List.copyOf(requireObject(owner, key).versions());
    }

    /** 列出调用方可见的对象键（不含已逻辑删除）。 */
    public List<String> listKeys(String owner) {
        requireText(owner, "owner");
        List<String> keys = new ArrayList<>();
        for (StoredObject o : metadataStore.listObjects(owner)) {
            ObjectVersion current = o.current();
            if (current != null && !current.deleteMarker()) {
                keys.add(o.key());
            }
        }
        return keys;
    }

    /** 删除当前版本（写入删除标记）；versionId 非空时物理移除指定版本并回收其引用。 */
    public void deleteObject(String owner, String key, String versionId) {
        metadataStore.inLock(() -> {
            StoredObject object = requireObject(owner, key);
            if (versionId == null) {
                // 每次删除都追加一个删除标记版本（S3 语义），版本历史与操作次数严格对应
                ObjectVersion marker = new ObjectVersion(
                        UUID.randomUUID().toString(), owner, List.of(), null, 0,
                        System.currentTimeMillis(), true);
                object.versions().add(marker);
                metadataStore.saveObject(object);
                log.info("deleteObject marker owner={} key={} markerId={}", owner, key, marker.versionId());
                return null;
            }
            boolean removed = object.versions().removeIf(v -> {
                if (!v.versionId().equals(versionId)) {
                    return false;
                }
                v.partHashes().forEach(contentStore::release);
                return true;
            });
            if (!removed) {
                throw new StoreException(StoreException.Code.NOT_FOUND,
                        "版本不存在: " + versionId);
            }
            if (object.versions().isEmpty()) {
                metadataStore.removeObject(owner, key);
            } else {
                metadataStore.saveObject(object);
            }
            log.info("deleteObject version owner={} key={} versionId={}", owner, key, versionId);
            return null;
        });
    }

    /** 当前调用方逻辑用量：存活版本字节 + 进行中上传的分片字节（先清扫过期会话，口径与配额一致）。 */
    public long usageOf(String owner) {
        return metadataStore.inLock(() -> {
            sweepExpired(owner);
            return logicalUsedBytes(owner);
        });
    }

    /** 当前调用方未完成（ACTIVE）上传占用的分片字节；过期会话容量已回收，不计入。 */
    public long unfinishedUploadBytes(String owner) {
        return metadataStore.inLock(() -> {
            sweepExpired(owner);
            return activeUploadBytes(owner);
        });
    }

    // ---- 内部辅助 ----

    /**
     * 后台清扫入口：不依赖任何后续请求，遍历所有调用方的 ACTIVE 会话并回收空闲超时者。
     * 与普通请求共用同一把元数据锁，因此和续传/上报分片天然互斥，一次过期恰好回收一次。
     *
     * @return 本次实际回收的会话数
     */
    public int sweepAllExpired() {
        return metadataStore.inLock(() -> {
            int count = 0;
            for (MultipartUpload u : metadataStore.allUploads()) {
                if (u.state() == MultipartUpload.State.ACTIVE && expireIfIdle(u)) {
                    count++;
                }
            }
            if (count > 0) {
                log.info("background sweep expired uploads count={}", count);
            }
            return count;
        });
    }

    /**
     * 清扫调用方所有连续空闲超时的 ACTIVE 会话：回收分片引用并置为 EXPIRED。
     * 必须在元数据锁内调用，与续传/上报分片互斥，容量释放恰好一次。
     */
    private void sweepExpired(String owner) {
        for (MultipartUpload u : metadataStore.uploadsOf(owner)) {
            if (u.state() == MultipartUpload.State.ACTIVE) {
                expireIfIdle(u);
            }
        }
    }

    /**
     * 判定单个会话是否已连续空闲超时；若是则回收容量并标记 EXPIRED（幂等：只回收一次）。
     * 必须在元数据锁内调用。返回 true 表示本次调用完成了过期回收。
     */
    private boolean expireIfIdle(MultipartUpload upload) {
        long idleTtl = config.getUploadIdleTtlMillis();
        if (idleTtl <= 0 || upload.state() != MultipartUpload.State.ACTIVE) {
            return false;
        }
        long now = clock.getAsLong();
        if (now - upload.lastInteractAtMillis() <= idleTtl) {
            return false;
        }
        long released = upload.parts().values().stream().mapToLong(PartInfo::size).sum();
        for (PartInfo part : upload.parts().values()) {
            contentStore.release(part.sha256());
        }
        upload.parts().clear();
        upload.state(MultipartUpload.State.EXPIRED);
        metadataStore.saveUpload(upload);
        log.info("uploadExpired uploadId={} owner={} key={} idleMs={} releasedBytes={}",
                upload.uploadId(), upload.owner(), upload.key(),
                now - upload.lastInteractAtMillis(), released);
        return true;
    }

    private static StoreException expiredException(MultipartUpload upload) {
        return new StoreException(StoreException.Code.UPLOAD_EXPIRED,
                "上传会话已过期且容量已回收，不能续传或完成: " + upload.uploadId()
                        + " expiredAt=" + upload.expiresAtMillis());
    }

    /** 仅统计 ACTIVE 会话：已完成会话的分片引用已转移给版本，已中止/已过期会话已回收。 */
    private long activeUploadBytes(String owner) {
        long sum = 0;
        for (MultipartUpload u : metadataStore.uploadsOf(owner)) {
            if (u.state() == MultipartUpload.State.ACTIVE) {
                sum += u.parts().values().stream().mapToLong(PartInfo::size).sum();
            }
        }
        return sum;
    }

    /**
     * 配额预检：按当前已持锁内看到的状态汇总用量，不触发清扫。
     * 调用方必须在进锁后先执行一次 {@link #sweepExpired}，避免持锁段内再次读时钟
     * 把“当前这个请求自己的会话”清扫掉（那会造成返回成功却已过期、块无法回收）。
     */
    private void checkQuotaNoSweep(String owner, long additionalBytes) {
        long used = logicalUsedBytes(owner);
        if (used + additionalBytes > config.getQuotaBytes()) {
            log.info("quota exceeded owner={} used={} additional={} quota={}",
                    owner, used, additionalBytes, config.getQuotaBytes());
            throw new StoreException(StoreException.Code.QUOTA_EXCEEDED,
                    "配额超限: used=" + used + " additional=" + additionalBytes
                            + " quota=" + config.getQuotaBytes());
        }
    }

    /** 调用方当前逻辑用量（存活版本字节 + ACTIVE 上传分片字节），必须在元数据锁内调用。 */
    private long logicalUsedBytes(String owner) {
        long used = 0;
        for (StoredObject o : metadataStore.listObjects(owner)) {
            for (ObjectVersion v : o.versions()) {
                if (!v.deleteMarker()) {
                    used += v.size();
                }
            }
        }
        return used + activeUploadBytes(owner);
    }

    private byte[] assemble(MultipartUpload upload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (PartInfo part : upload.parts().values()) {
            byte[] data = contentStore.get(part.sha256());
            if (data == null) {
                throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                        "分片内容丢失: part=" + part.partNumber() + " sha=" + part.sha256());
            }
            if (data.length != part.size() || !ContentStore.sha256Hex(data).equals(part.sha256())) {
                throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                        "分片内容损坏: part=" + part.partNumber());
            }
            out.writeBytes(data);
        }
        return out.toByteArray();
    }

    private byte[] readVersion(ObjectVersion version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String sha : version.partHashes()) {
            byte[] data = contentStore.get(sha);
            if (data == null) {
                throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                        "版本内容丢失: versionId=" + version.versionId() + " sha=" + sha);
            }
            out.writeBytes(data);
        }
        byte[] assembled = out.toByteArray();
        if (!ContentStore.sha256Hex(assembled).equals(version.contentHash())) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                    "版本内容校验失败: versionId=" + version.versionId());
        }
        return assembled;
    }

    private MultipartUpload requireUpload(String owner, String uploadId) {
        requireText(owner, "owner");
        MultipartUpload upload = metadataStore.findUpload(uploadId)
                .filter(u -> u.owner().equals(owner))
                .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND,
                        "上传会话不存在: " + uploadId));
        return upload;
    }

    private StoredObject requireObject(String owner, String key) {
        requireText(owner, "owner");
        requireText(key, "key");
        return metadataStore.findObject(owner, key)
                .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND,
                        "对象不存在: " + key));
    }

    private ObjectVersion findVersion(String owner, String key, String versionId) {
        return requireObject(owner, key).versions().stream()
                .filter(v -> v.versionId().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND,
                        "版本不存在: " + versionId));
    }

    private void requireActive(MultipartUpload upload) {
        if (upload.state() != MultipartUpload.State.ACTIVE) {
            throw new StoreException(StoreException.Code.INVALID_STATE,
                    "上传会话非活动状态: " + upload.uploadId() + " state=" + upload.state());
        }
    }

    /**
     * 续传/完成前的纯状态判定，必须在元数据锁内、且调用方已先做过过期清扫后使用：
     * 已过期（含被并发请求或后台清扫刚标记过期）→ UPLOAD_EXPIRED；
     * 其他非活动状态（已中止/已完成）→ INVALID_STATE。
     * 本方法本身不读时钟、不做清扫，避免持锁段内判定结果随时间变化。
     */
    private void requireUsable(MultipartUpload upload) {
        if (upload.state() == MultipartUpload.State.EXPIRED) {
            throw expiredException(upload);
        }
        requireActive(upload);
    }

    /** 滑动续期：任何会话交互都按当前时钟顺延过期时间，已上传分片不因连续活跃而超时。 */
    private void touch(MultipartUpload upload) {
        long now = clock.getAsLong();
        upload.interactedAt(now, now + config.getUploadTtlMillis());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new StoreException(StoreException.Code.BAD_REQUEST, name + " 不能为空");
        }
    }
}
