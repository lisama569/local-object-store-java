package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 重启场景：未完成上传与已提交对象的元数据在进程重启后不得错乱。 */
class RestartRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RestartRecoveryTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void committedObjectsSurviveRestart() {
        byte[] data = TestSupport.bytes("persist-", 72);
        ObjectStoreService svc1 = TestSupport.newService(dir);
        ObjectVersion v = svc1.putObject(OWNER, "k", data, null);
        log.info("input: committed version {} then restart", v.versionId());

        ObjectStoreService svc2 = TestSupport.newService(dir);
        assertArrayEquals(data, svc2.getObject(OWNER, "k"), "重启后已提交对象必须可读");
        assertEquals(1, svc2.listVersions(OWNER, "k").size());
        assertEquals(v.versionId(), svc2.listVersions(OWNER, "k").get(0).versionId(),
                "重启后版本号必须保持一致");
    }

    @Test
    void unfinishedUploadResumableAfterRestart() {
        byte[] p1 = TestSupport.bytes("before-restart-", 48);
        byte[] p2 = TestSupport.bytes("after-restart--", 48);
        ObjectStoreService svc1 = TestSupport.newService(dir);
        MultipartUpload up = svc1.createUpload(OWNER, "big");
        svc1.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        log.info("input: restart with 1 of 2 parts uploaded, uploadId={}", up.uploadId());

        ObjectStoreService svc2 = TestSupport.newService(dir);
        MultipartUpload resumed = svc2.getUpload(OWNER, up.uploadId());
        assertEquals(1, resumed.parts().size(), "重启后未完成上传的分片不得丢失");
        log.info("decision: resumed parts={}", resumed.parts().keySet());

        svc2.uploadPart(OWNER, up.uploadId(), 2, p2, null);
        ObjectVersion v = svc2.completeUpload(OWNER, up.uploadId(), null);
        assertEquals(96, v.size());
        byte[] expected = new byte[96];
        System.arraycopy(p1, 0, expected, 0, 48);
        System.arraycopy(p2, 0, expected, 48, 48);
        assertArrayEquals(expected, svc2.getObject(OWNER, "big"));
    }

    @Test
    void dedupRefcountsSurviveRestart() {
        byte[] data = TestSupport.bytes("shared-restart-", 56);
        ObjectStoreService svc1 = TestSupport.newService(dir);
        ObjectVersion v1 = svc1.putObject(OWNER, "a", data, null);
        svc1.putObject(OWNER, "b", data, null);
        log.info("input: two refs to same content, delete one after restart");

        ObjectStoreService svc2 = TestSupport.newService(dir);
        svc2.deleteObject(OWNER, "a", v1.versionId());
        assertArrayEquals(data, svc2.getObject(OWNER, "b"),
                "重启后引用计数不得错乱，剩余引用必须可读");

        ObjectStoreService svc3 = TestSupport.newService(dir);
        assertArrayEquals(data, svc3.getObject(OWNER, "b"),
                "再次重启后数据仍须完整");
    }

    @Test
    void usageConsistentAfterRestart() {
        ObjectStoreService svc1 = TestSupport.newService(dir);
        svc1.putObject(OWNER, "k1", TestSupport.bytes("u1-", 30), null);
        MultipartUpload up = svc1.createUpload(OWNER, "k2");
        svc1.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("u2-", 20), null);
        long before = svc1.usageOf(OWNER);
        log.info("input: usage before restart={}", before);

        ObjectStoreService svc2 = TestSupport.newService(dir);
        assertEquals(before, svc2.usageOf(OWNER),
                "重启后配额用量统计必须一致（含进行中上传的分片）");
    }

    @Test
    void expiredUploadStaysExpiredAndReleasedAfterRestart() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc1 = TestSupport.newService(dir, 1024, 1024, 1000, clock.supplier());
        MultipartUpload up = svc1.createUpload(OWNER, "expiring");
        svc1.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("gone-", 40), null);
        clock.advance(2000);
        assertEquals(0, svc1.usageOf(OWNER), "过期后容量必须已回收");
        assertEquals(MultipartUpload.State.EXPIRED, svc1.peekUploadState(OWNER, up.uploadId()));
        log.info("input: upload expired before restart, uploadId={}", up.uploadId());

        // 重启后（系统时钟远在基准时钟之后，空闲必然超时）状态与容量仍须保持
        ObjectStoreService svc2 = TestSupport.newService(dir);
        assertEquals(MultipartUpload.State.EXPIRED, svc2.peekUploadState(OWNER, up.uploadId()),
                "EXPIRED 状态必须持久化，重启后不得复活");
        assertEquals(0, svc2.usageOf(OWNER), "重启后已释放容量不得被重新计入");
        StoreException e = assertThrows(StoreException.class, () ->
                svc2.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("x-", 5), null));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code(), "重启后续传过期会话仍须被拒绝");
        log.info("decision: expiry persisted across restart code={}", e.code());
    }
}
