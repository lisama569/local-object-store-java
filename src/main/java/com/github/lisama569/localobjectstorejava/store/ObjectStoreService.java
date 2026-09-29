package com.github.lisama569.localobjectstorejava.store;

import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartInfo;
import com.github.lisama569.localobjectstorejava.store.model.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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

    public ObjectStoreService(StorageConfig config, ContentStore contentStore, MetadataStore metadataStore) {
        this.config = config;
        this.contentStore = contentStore;
        this.metadataStore = metadataStore;
    }

    /** 创建分片上传会话（不声明分片总数，老流程）。 */
    public MultipartUpload createUpload(String owner, String key) {
        return createUpload(owner, key, null);
    }

    /**
     * 创建分片上传会话。
     * @param expectedTotalParts 可选的分片总数声明；null 表示不声明（老流程）。
     *                           非 null 时完成要求分片恰好覆盖 1..N。
     */
    public MultipartUpload createUpload(String owner, String key, Integer expectedTotalParts) {
        requireText(owner, "owner");
        requireText(key, "key");
        if (expectedTotalParts != null
                && (expectedTotalParts < 1 || expectedTotalParts > config.getMaxParts())) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "声明的分片总数越界: " + expectedTotalParts
                            + " (允许 1.." + config.getMaxParts() + ")");
        }
        long now = System.currentTimeMillis();
        MultipartUpload upload = new MultipartUpload(
                UUID.randomUUID().toString(), owner, key, now, now + config.getUploadTtlMillis(),
                expectedTotalParts);
        metadataStore.saveUpload(upload);
        log.info("createUpload owner={} key={} uploadId={} expectedTotalParts={}",
                owner, key, upload.uploadId(), expectedTotalParts);
        return upload;
    }

    /**
     * 查询上传会话（含已提交分片），越权与不存在均返回 NOT_FOUND；
     * 已过期会话返回 UPLOAD_EXPIRED。有效会话查询会顺延过期时间。
     */
    public MultipartUpload getUpload(String owner, String uploadId) {
        return metadataStore.inLock(() -> {
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.EXPIRED || expireIfDue(upload)) {
                throw expiredException(upload);
            }
            if (upload.state() != MultipartUpload.State.ACTIVE) {
                return upload;
            }
            touch(upload);
            metadataStore.saveUpload(upload);
            return upload;
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
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (expireIfDue(upload)) {
                throw expiredException(upload);
            }
            requireActive(upload);
            Integer declared = upload.expectedTotalParts();
            if (declared != null && partNumber > declared) {
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "分片序号超过声明总数: part=" + partNumber + " declaredTotal=" + declared);
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
            checkQuota(owner, data.length);
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
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.COMPLETED) {
                // 幂等：返回首次完成产生的版本
                return findVersion(owner, upload.key(), upload.completedVersionId());
            }
            if (expireIfDue(upload)) {
                throw expiredException(upload);
            }
            requireActive(upload);
            if (upload.parts().isEmpty()) {
                throw new StoreException(StoreException.Code.INVALID_STATE,
                        "上传会话没有任何分片，无法完成: " + uploadId);
            }
            Integer declared = upload.expectedTotalParts();
            if (declared != null) {
                List<Integer> missing = new ArrayList<>();
                for (int n = 1; n <= declared; n++) {
                    if (!upload.parts().containsKey(n)) {
                        missing.add(n);
                    }
                }
                if (!missing.isEmpty()) {
                    log.info("completeUpload missing parts uploadId={} declared={} present={} missing={}",
                            uploadId, declared, upload.parts().keySet(), missing);
                    throw new StoreException(StoreException.Code.MISSING_PARTS,
                            "分片未到齐: 声明总数=" + declared + " 缺少分片号=" + missing);
                }
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

    /** 中止上传并回收分片占用；已过期会话按幂等成功处理（占用在过期时已释放）。 */
    public void abortUpload(String owner, String uploadId) {
        metadataStore.inLock(() -> {
            MultipartUpload upload = requireUpload(owner, uploadId);
            if (upload.state() == MultipartUpload.State.COMPLETED) {
                throw new StoreException(StoreException.Code.INVALID_STATE,
                        "上传已完成，不能中止: " + uploadId);
            }
            if (upload.state() == MultipartUpload.State.ABORTED) {
                log.info("abortUpload already aborted owner={} uploadId={}", owner, uploadId);
                return null;
            }
            if (upload.state() == MultipartUpload.State.EXPIRED || expireIfDue(upload)) {
                log.info("abortUpload on expired upload owner={} uploadId={}", owner, uploadId);
                return null;
            }
            for (PartInfo part : upload.parts().values()) {
                contentStore.release(part.sha256());
            }
            upload.state(MultipartUpload.State.ABORTED);
            metadataStore.saveUpload(upload);
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
            checkQuota(owner, data.length);
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

    /** 一次区间读取结果：切片内容与服务端实际采用的闭区间（含两端，0 基）。 */
    public record Range(byte[] data, int startInclusive, int endInclusive, long totalSize) {
    }

    /** 按字节区间读取当前版本；闭区间、0 基，先做完整内容校验再切片。 */
    public Range getObjectRange(String owner, String key, Long start, Long end) {
        StoredObject object = requireObject(owner, key);
        ObjectVersion current = object.current();
        if (current == null || current.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND, "对象不存在: " + key);
        }
        return readVersionRange(current, start, end);
    }

    /** 按字节区间读取指定版本，权限与版本判定与整段读取完全一致。 */
    public Range getObjectVersionRange(String owner, String key, String versionId,
                                       Long start, Long end) {
        ObjectVersion version = findVersion(owner, key, versionId);
        if (version.deleteMarker()) {
            throw new StoreException(StoreException.Code.NOT_FOUND,
                    "版本是删除标记，无内容: " + versionId);
        }
        return readVersionRange(version, start, end);
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

    /** 当前调用方逻辑用量：存活版本字节 + 进行中上传的分片字节。 */
    public long usageOf(String owner) {
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

    /** 某调用方当前未完成（ACTIVE）上传占用的字节；与配额口径中的上传部分完全一致。 */
    public long incompleteUploadBytesOf(String owner) {
        requireText(owner, "owner");
        return activeUploadBytes(owner);
    }

    /** 按 uploadId 读取会话当前状态，不做过期判定与续期（供测试与运维观测）。 */
    public MultipartUpload getUploadAnyState(String uploadId) {
        requireText(uploadId, "uploadId");
        return metadataStore.findUpload(uploadId)
                .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND,
                        "上传会话不存在: " + uploadId));
    }

    /** 测试辅助：把 ACTIVE 会话的过期时刻推到“刚好临界”，使下一次交互与回收竞争确定发生。 */
    public void armExpiryRace(String uploadId) {
        metadataStore.inLock(() -> {
            MultipartUpload upload = getUploadAnyState(uploadId);
            if (upload.state() == MultipartUpload.State.ACTIVE) {
                upload.touch(System.currentTimeMillis());
                metadataStore.saveUpload(upload);
            }
            return null;
        });
    }

    // ---- 内部辅助 ----

    /**
     * 惰性过期：必须在元数据锁内调用。
     * 判定与回收一次完成，和续传/上报分片互斥：要么交互先拿到锁并续期成功，
     * 要么本次扫描先把会话置为 EXPIRED 并释放容量，二者不可能同时发生。
     * @return true 表示本次调用执行了过期回收
     */
    private boolean expireIfDue(MultipartUpload upload) {
        if (upload.state() != MultipartUpload.State.ACTIVE) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now <= upload.expiresAtMillis()) {
            return false;
        }
        long freed = upload.parts().values().stream().mapToLong(PartInfo::size).sum();
        for (PartInfo part : upload.parts().values()) {
            contentStore.release(part.sha256());
        }
        upload.parts().clear();
        upload.state(MultipartUpload.State.EXPIRED);
        metadataStore.saveUpload(upload);
        log.info("upload expired uploadId={} owner={} key={} idleSince={} freedBytes={}",
                upload.uploadId(), upload.owner(), upload.key(), upload.expiresAtMillis(), freed);
        return true;
    }

    /** 后台扫描：回收所有连续空闲超阈值的会话，返回本次过期的会话数。 */
    public int sweepExpiredUploads() {
        return metadataStore.inLock(() -> {
            int count = 0;
            for (MultipartUpload u : metadataStore.allUploads()) {
                if (expireIfDue(u)) {
                    count++;
                }
            }
            if (count > 0) {
                log.info("sweep expired uploads count={}", count);
            }
            return count;
        });
    }

    private static StoreException expiredException(MultipartUpload upload) {
        return new StoreException(StoreException.Code.UPLOAD_EXPIRED,
                "上传会话已过期（连续空闲超时），占用已释放，不能续传或完成: " + upload.uploadId());
    }

    /** 仅统计 ACTIVE 会话：已完成会话的分片引用已转移给版本，已中止会话已回收。 */
    private long activeUploadBytes(String owner) {
        long sum = 0;
        for (MultipartUpload u : metadataStore.uploadsOf(owner)) {
            if (u.state() == MultipartUpload.State.ACTIVE) {
                sum += u.parts().values().stream().mapToLong(PartInfo::size).sum();
            }
        }
        return sum;
    }

    private void checkQuota(String owner, long additionalBytes) {
        long used = usageOf(owner);
        if (used + additionalBytes > config.getQuotaBytes()) {
            log.info("quota exceeded owner={} used={} additional={} quota={}",
                    owner, used, additionalBytes, config.getQuotaBytes());
            throw new StoreException(StoreException.Code.QUOTA_EXCEEDED,
                    "配额超限: used=" + used + " additional=" + additionalBytes
                            + " quota=" + config.getQuotaBytes());
        }
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

    /**
     * 区间读取：闭区间 [start,end]，下标 0 基。
     * 先经 {@link #readVersion} 完整拼接并重校整体哈希（不绕过内容校验），再切片。
     * 参数格式非法（null、负数、start&gt;end）→ BAD_REQUEST；
     * 起点越过对象末尾 → RANGE_NOT_SATISFIABLE。end 超出末尾按实际末尾截断。
     */
    private Range readVersionRange(ObjectVersion version, Long start, Long end) {
        if (start == null || end == null) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "区间读取必须同时提供 start 与 end（闭区间，0 基）");
        }
        if (start < 0 || end < 0) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "区间端点不能为负: start=" + start + " end=" + end);
        }
        if (start > end) {
            throw new StoreException(StoreException.Code.BAD_REQUEST,
                    "区间起点不能大于终点: start=" + start + " end=" + end);
        }
        byte[] full = readVersion(version);
        long length = full.length;
        log.info("readRange versionId={} length={} requested=[{},{}]",
                version.versionId(), length, start, end);
        if (start >= length) {
            throw new StoreException(StoreException.Code.RANGE_NOT_SATISFIABLE,
                    "区间起点超出对象长度: start=" + start + " length=" + length);
        }
        long effEnd = Math.min(end, length - 1);
        byte[] slice = Arrays.copyOfRange(full, start.intValue(), (int) effEnd + 1);
        return new Range(slice, start.intValue(), (int) effEnd, length);
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
        if (upload.state() == MultipartUpload.State.EXPIRED) {
            throw expiredException(upload);
        }
        if (upload.state() != MultipartUpload.State.ACTIVE) {
            throw new StoreException(StoreException.Code.INVALID_STATE,
                    "上传会话非活动状态: " + upload.uploadId() + " state=" + upload.state());
        }
    }

    /** 滑动续期：任何会话交互都顺延过期时间，已上传分片不因超时丢失。 */
    private void touch(MultipartUpload upload) {
        upload.touch(System.currentTimeMillis() + config.getUploadTtlMillis());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new StoreException(StoreException.Code.BAD_REQUEST, name + " 不能为空");
        }
    }
}
