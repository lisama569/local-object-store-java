package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整性校验：分片级与对象级两层；失败原因可区分、无半成品、容量不泄漏、会话可恢复。
 */
class ChecksumFailureTest extends StoreTestSupport {

    private byte[][] parts;
    private byte[] fullContent;

    @BeforeEach
    void setUp() {
        buildStore();
        parts = new byte[][]{bytes("aaaa"), bytes("bbbb"), bytes("cccc")};
        fullContent = concat(parts);
    }

    @Test
    @DisplayName("分片摘要不一致：CHECKSUM_MISMATCH，分片不落盘不计费，可重传后成功")
    void partChecksumMismatch() {
        var caller = tenant("t1");
        MultipartUpload upload = service.initiate(caller, "obj/corrupt-part", 3, null, null);
        long usedBefore = service.capacityUsed();
        String wrongSha = sha256(bytes("zzzz"));
        log.info("[case-input] part=1 actualSha={} declaredSha={}",
                sha256(parts[0]), wrongSha);

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, upload.uploadId(), 1,
                        stream(parts[0]), wrongSha));
        assertEquals(ErrorType.CHECKSUM_MISMATCH, ex.getType());
        assertTrue(ex.getMessage().contains("part checksum mismatch"));
        log.info("[judge] part rejected: {} - {}", ex.getType(), ex.getMessage());

        assertEquals(usedBefore, service.capacityUsed(), "校验失败的分片不得占用容量");
        assertEquals(0, service.getUpload(caller, upload.uploadId()).parts().size());

        // 用正确摘要重传，整个上传仍可成功（会话没有被错误终结）
        for (int i = 1; i <= 3; i++) {
            service.uploadPart(caller, upload.uploadId(), i,
                    stream(parts[i - 1]), sha256(parts[i - 1]));
        }
        ObjectVersion v = service.completeUpload(caller, upload.uploadId(), sha256(fullContent));
        assertEquals(sha256(fullContent), v.contentSha256());
    }

    @Test
    @DisplayName("整对象摘要不一致（分片各自正确但对象级声明错误）：拒绝且无版本可读")
    void objectChecksumMismatch() {
        var caller = tenant("t1");
        MultipartUpload upload = service.initiate(caller, "obj/wrong-full", 3, null, null);
        for (int i = 1; i <= 3; i++) {
            service.uploadPart(caller, upload.uploadId(), i,
                    stream(parts[i - 1]), sha256(parts[i - 1]));
        }
        String wrongFull = sha256(bytes("different-whole-content"));
        log.info("[case-input] actualObjectSha={} declaredObjectSha={}",
                sha256(fullContent), wrongFull);

        long usedBeforeComplete = service.capacityUsed();
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.completeUpload(caller, upload.uploadId(), wrongFull));
        assertEquals(ErrorType.CHECKSUM_MISMATCH, ex.getType());
        assertTrue(ex.getMessage().contains("object checksum mismatch"));
        log.info("[judge] object rejected: {} - {}", ex.getType(), ex.getMessage());

        // 不产生任何可读版本
        ObjectStoreException nf = assertThrows(ObjectStoreException.class,
                () -> service.get(caller, "obj/wrong-full", null));
        assertEquals(ErrorType.NOT_FOUND, nf.getType());
        // 分片在途容量仍保留（会话仍 ACTIVE，客户端可带正确摘要重试），无半成品对象
        assertEquals(usedBeforeComplete, service.capacityUsed());
        assertTrue(service.getUpload(caller, upload.uploadId()).isActive());

        ObjectVersion v = service.completeUpload(caller, upload.uploadId(),
                sha256(fullContent));
        assertNotNull(v);
    }

    @Test
    @DisplayName("simple put 摘要不一致：拒绝，不产生版本且容量零占用")
    void simplePutChecksumMismatch() {
        var caller = tenant("t1");
        byte[] data = repeated((byte) 7, 200);
        String wrong = sha256(repeated((byte) 8, 200));
        log.info("[case-input] size={} declaredSha={}", data.length, wrong);

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.put(caller, "put/bad", stream(data), wrong));
        assertEquals(ErrorType.CHECKSUM_MISMATCH, ex.getType());
        assertEquals(0, service.capacityUsed());
        assertThrows(ObjectStoreException.class,
                () -> service.get(caller, "put/bad", null));
        log.info("[judge] simple put rejected cleanly: {}", ex.getMessage());
    }
}
