package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 分片上传：乱序、重复、续传、完成与校验失败。 */
class MultipartUploadTest {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void outOfOrderPartsCompleteToDeterministicContent() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] p1 = TestSupport.bytes("part-one-", 100);
        byte[] p2 = TestSupport.bytes("part-two-", 100);
        byte[] p3 = TestSupport.bytes("part-three-", 100);
        log.info("input: 3 parts of 100 bytes each, submitted out of order 3,1,2");

        MultipartUpload up = svc.createUpload(OWNER, "k1");
        svc.uploadPart(OWNER, up.uploadId(), 3, p3, null);
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 2, p2, null);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(p1);
        expected.writeBytes(p2);
        expected.writeBytes(p3);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(),
                ContentStore.sha256Hex(expected.toByteArray()));
        log.info("decision: parts assembled by partNumber, versionId={} contentHash={}",
                v.versionId(), v.contentHash());

        assertArrayEquals(expected.toByteArray(), svc.getObject(OWNER, "k1"));
    }

    @Test
    void duplicatePartSameContentIsIdempotent() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] p1 = TestSupport.bytes("dup-", 64);
        MultipartUpload up = svc.createUpload(OWNER, "k2");

        PartInfo first = svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        PartInfo second = svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        log.info("input: same part submitted twice; first={} second={}", first, second);

        assertEquals(first, second, "重复提交相同内容必须幂等返回同一分片");
        assertEquals(1, svc.getUpload(OWNER, up.uploadId()).parts().size(),
                "重复分片不得重复计入");
        assertEquals(64, svc.usageOf(OWNER), "重复分片不得重复占用配额");
    }

    @Test
    void duplicatePartDifferentContentConflicts() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k3");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("aaa-", 32), null);

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("bbb-", 32), null));
        log.info("input: same partNumber with different content; decision code={}", e.code());
        assertEquals(StoreException.Code.CONFLICT, e.code());
    }

    @Test
    void resumeAcrossSessionsKeepsParts() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] p1 = TestSupport.bytes("resume-1-", 50);
        byte[] p2 = TestSupport.bytes("resume-2-", 50);
        MultipartUpload up = svc.createUpload(OWNER, "k4");
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        log.info("input: session interrupted after part 1; uploadId={}", up.uploadId());

        // 模拟新会话：仅凭 uploadId 查询已上传分片并续传
        MultipartUpload resumed = svc.getUpload(OWNER, up.uploadId());
        assertEquals(1, resumed.parts().size(), "续传时必须能看到已上传分片");
        log.info("decision: resumed upload has parts={}", resumed.parts().keySet());

        svc.uploadPart(OWNER, up.uploadId(), 2, p2, null);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(), null);
        assertEquals(100, v.size());
    }

    @Test
    void partChecksumMismatchRejectedAndNotStored() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] data = TestSupport.bytes("bad-part-", 40);
        String wrongSha = ContentStore.sha256Hex(TestSupport.bytes("other-", 40));
        MultipartUpload up = svc.createUpload(OWNER, "k5");
        log.info("input: part with wrong expected sha256={}", wrongSha);

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 1, data, wrongSha));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.CHECKSUM_MISMATCH, e.code());
        assertEquals(0, svc.getUpload(OWNER, up.uploadId()).parts().size(),
                "校验失败的分片不得入库");
        assertEquals(0, svc.usageOf(OWNER), "校验失败不得占用配额");
    }

    @Test
    void overallChecksumMismatchRejectedWithoutPartialObject() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k6");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("x-", 16), null);
        String wrongSha = ContentStore.sha256Hex(TestSupport.bytes("y-", 16));
        log.info("input: complete with wrong overall sha256={}", wrongSha);

        StoreException e = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), wrongSha));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.CHECKSUM_MISMATCH, e.code());
        assertThrows(StoreException.class, () -> svc.getObject(OWNER, "k6"),
                "校验失败不得留下可读对象");
        // 会话仍 ACTIVE，客户端可修正后重试
        assertEquals(MultipartUpload.State.ACTIVE, svc.getUpload(OWNER, up.uploadId()).state());
    }

    @Test
    void completeIsIdempotentAcrossRetries() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k7");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("z-", 24), null);

        ObjectVersion v1 = svc.completeUpload(OWNER, up.uploadId(), null);
        ObjectVersion v2 = svc.completeUpload(OWNER, up.uploadId(), null);
        log.info("input: complete retried; v1={} v2={}", v1.versionId(), v2.versionId());

        assertEquals(v1.versionId(), v2.versionId(), "重复完成必须返回同一版本");
        assertEquals(1, svc.listVersions(OWNER, "k7").size(), "重复完成不得产生重复版本");
    }

    @Test
    void abortReleasesQuotaAndBlocksFurtherParts() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k8");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("q-", 32), null);
        assertEquals(32, svc.usageOf(OWNER));

        svc.abortUpload(OWNER, up.uploadId());
        log.info("decision: aborted, usage={}", svc.usageOf(OWNER));
        assertEquals(0, svc.usageOf(OWNER), "中止必须释放分片占用");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("q2-", 8), null));
        assertEquals(StoreException.Code.INVALID_STATE, e.code());
    }
}
