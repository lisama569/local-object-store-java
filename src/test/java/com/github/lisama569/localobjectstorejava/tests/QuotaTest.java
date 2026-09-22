package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配额与分片大小上限：超限按策略拒绝，失败后无残留。 */
class QuotaTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void quotaExceededRejectedWithReasonAndNoResidue() {
        ObjectStoreService svc = TestSupport.newService(dir, 100, 1024);
        svc.putObject(OWNER, "k1", TestSupport.bytes("a-", 80), null);
        log.info("input: quota=100, used=80, attempt to write 40 more");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.putObject(OWNER, "k2", TestSupport.bytes("b-", 40), null));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.QUOTA_EXCEEDED, e.code());
        assertEquals(80, svc.usageOf(OWNER), "失败后不得占用未释放的容量");
        assertThrows(StoreException.class, () -> svc.getObject(OWNER, "k2"),
                "失败写入不得留下可读对象");
    }

    @Test
    void quotaCountsActiveUploadParts() {
        ObjectStoreService svc = TestSupport.newService(dir, 100, 1024);
        MultipartUpload up = svc.createUpload(OWNER, "k");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("p-", 60), null);
        log.info("input: quota=100, active upload holds 60 bytes, attempt 50 more");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("q-", 50), null));
        log.info("decision: code={}", e.code());

        assertEquals(StoreException.Code.QUOTA_EXCEEDED, e.code());
        assertEquals(1, svc.getUpload(OWNER, up.uploadId()).parts().size(),
                "超限分片不得入库");
        assertEquals(60, svc.usageOf(OWNER));
    }

    @Test
    void quotaIsPerOwner() {
        ObjectStoreService svc = TestSupport.newService(dir, 100, 1024);
        svc.putObject("alice", "k", TestSupport.bytes("a-", 90), null);
        log.info("input: alice used 90/100; bob writes 90");

        svc.putObject("bob", "k", TestSupport.bytes("b-", 90), null);
        assertEquals(90, svc.usageOf("bob"), "配额按调用方隔离");
    }

    @Test
    void oversizedPartRejected() {
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 16);
        MultipartUpload up = svc.createUpload(OWNER, "k");
        log.info("input: maxPartSize=16, attempt 32-byte part");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("big-", 32), null));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.PART_SIZE_EXCEEDED, e.code());
        assertEquals(0, svc.getUpload(OWNER, up.uploadId()).parts().size());
        assertEquals(0, svc.usageOf(OWNER));
    }

    @Test
    void usageReleasedAfterVersionDeleteAllowsNewWrites() {
        ObjectStoreService svc = TestSupport.newService(dir, 100, 1024);
        var v = svc.putObject(OWNER, "k", TestSupport.bytes("a-", 90), null);
        svc.deleteObject(OWNER, "k", v.versionId());
        log.info("input: deleted 90-byte version, usage={}", svc.usageOf(OWNER));

        assertEquals(0, svc.usageOf(OWNER), "物理删除版本必须释放配额");
        svc.putObject(OWNER, "k2", TestSupport.bytes("b-", 90), null);
        assertEquals(90, svc.usageOf(OWNER));
    }
}
