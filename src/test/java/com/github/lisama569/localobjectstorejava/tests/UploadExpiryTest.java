package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未完成上传的空闲过期、容量回收，以及过期与并发续传/上报分片之间的竞争。 */
class UploadExpiryTest {

    private static final Logger log = LoggerFactory.getLogger(UploadExpiryTest.class);
    private static final String OWNER = "alice";
    private static final long IDLE_TTL = 1000;

    @TempDir
    Path dir;

    @Test
    void idleExpiryReleasesCapacityAndBlocksFurtherOps() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 1024, IDLE_TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "k1");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("p-", 60), null);
        assertEquals(60, svc.usageOf(OWNER));
        assertEquals(60, svc.unfinishedUploadBytes(OWNER));
        log.info("input: upload holds 60 bytes, advance clock by ttl+1={} ms", IDLE_TTL + 1);
        clock.advance(IDLE_TTL + 1);

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("q-", 10), null));
        log.info("decision: uploadPart after idle code={} message={}", e.code(), e.getMessage());
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code(), "过期必须与其他失败原因区分");

        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, up.uploadId()));
        assertEquals(0, svc.usageOf(OWNER), "过期后容量必须真正释放");
        assertEquals(0, svc.unfinishedUploadBytes(OWNER), "未完成占用查询必须与用量口径一致");

        StoreException complete = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), null));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, complete.code(), "过期会话不能完成");
    }

    @Test
    void anyInteractionSlidesIdleWindow() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 1024, IDLE_TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "k2");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("p-", 20), null);
        // 每次推进都小于 TTL，期间有查询/续传交互，会话始终不过期
        for (int i = 0; i < 5; i++) {
            clock.advance(IDLE_TTL - 100);
            if (i % 2 == 0) {
                svc.getUpload(OWNER, up.uploadId());
            } else {
                // 内容/大小各不相同，避免内容去重导致占用与计数偏小
                svc.uploadPart(OWNER, up.uploadId(), i + 2,
                        TestSupport.bytes("x-" + i + "-", 5 + i), null);
            }
        }
        log.info("decision: 5 interactions kept the upload alive across {} ms (ttl={})",
                5L * (IDLE_TTL - 100), IDLE_TTL);
        assertEquals(MultipartUpload.State.ACTIVE, svc.getUpload(OWNER, up.uploadId()).state(),
                "连续活跃（间隔 < TTL）不得被过期");
        // part1=20；i=1 上传 part3 大小 5+1=6；i=3 上传 part5 大小 5+3=8 → 合计 34
        long held = svc.unfinishedUploadBytes(OWNER);
        log.info("decision: held bytes after interleaved activity = {}", held);
        assertEquals(34, held, "所有成功上传分片都应计入未完成占用");
    }

    @Test
    void expiryReleasesExactlyOnceUnderConcurrentParts() throws Exception {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 1024, IDLE_TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "k3");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 30), null);
        long usageAfterSeed = svc.usageOf(OWNER);
        log.info("input: seeded 30 bytes usage={}, advance to ttl boundary, race {} part uploads",
                usageAfterSeed, 8);
        clock.advance(IDLE_TTL + 1);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger stored = new AtomicInteger();
        java.util.List<StoreException.Code> unexpected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int partNo = i + 2;
            pool.submit(() -> {
                try {
                    start.await();
                    svc.uploadPart(OWNER, up.uploadId(), partNo,
                            TestSupport.bytes("race-" + partNo + "-", 7 + partNo), null);
                    stored.incrementAndGet();
                } catch (StoreException se) {
                    if (se.code() == StoreException.Code.UPLOAD_EXPIRED) {
                        expired.incrementAndGet();
                    } else {
                        unexpected.add(se.code());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("decision: stored={} expired={} (exactly one winner outcome class expected)",
                stored.get(), expired.get());

        assertEquals(threads, stored.get() + expired.get(), "每个请求要么成功续上要么明确过期");
        assertTrue(unexpected.isEmpty(), "除成功/明确过期外不得出现其他失败: " + unexpected);
        // 已越过空闲阈值：锁内先判定过期，所有请求都必须收敛为过期（无请求能复活会话）
        assertEquals(0, stored.get(), "越过空闲阈值后不得有请求成功并复活会话");
        assertEquals(threads, expired.get());
        assertEquals(0, svc.usageOf(OWNER), "过期回收恰好一次，容量不得重复/漏算");
        assertEquals(0, svc.unfinishedUploadBytes(OWNER));
        // 分片引用也必须已全部从内容存储释放（30 字节的 seed 块无其他引用）
        assertEquals(0, physicalBlobBytes(svc), "物理内容块引用必须归零");
    }

    @Test
    void interactionExactlyAtTtlBoundaryKeepsUploadAlive() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 1024, IDLE_TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "k4");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("p-", 10), null);
        clock.advance(IDLE_TTL); // 恰好等于 TTL：now-last == ttl，不算“连续空闲超过”
        log.info("input: idle exactly ttl={} (boundary must remain alive)", IDLE_TTL);
        // 用不续期的状态查看判定，避免 getUpload 的滑动续期干扰边界
        assertEquals(MultipartUpload.State.ACTIVE,
                svc.peekUploadState(OWNER, up.uploadId()), "边界（==ttl）不得过期");
        assertEquals(10, svc.unfinishedUploadBytes(OWNER), "边界（==ttl）容量仍应计入");
        clock.advance(1);
        assertEquals(MultipartUpload.State.EXPIRED,
                svc.peekUploadState(OWNER, up.uploadId()), "再超过 1ms 必须过期");
        assertEquals(0, svc.unfinishedUploadBytes(OWNER), "过期后容量必须回收");
        StoreException e = assertThrows(StoreException.class, () ->
                svc.getUpload(OWNER, up.uploadId()));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
    }

    @Test
    void completedAndAbortedUploadsNeverExpire() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024, 1024, IDLE_TTL, clock.supplier());
        MultipartUpload done = svc.createUpload(OWNER, "k5");
        svc.uploadPart(OWNER, done.uploadId(), 1, TestSupport.bytes("d-", 12), null);
        svc.completeUpload(OWNER, done.uploadId(), null);
        MultipartUpload aborted = svc.createUpload(OWNER, "k6");
        svc.uploadPart(OWNER, aborted.uploadId(), 1, TestSupport.bytes("a-", 8), null);
        svc.abortUpload(OWNER, aborted.uploadId());
        clock.advance(IDLE_TTL * 10);
        // 触发清扫
        assertEquals(0, svc.unfinishedUploadBytes(OWNER));
        assertEquals(MultipartUpload.State.COMPLETED, svc.getUpload(OWNER, done.uploadId()).state());
        assertEquals(MultipartUpload.State.ABORTED, svc.getUpload(OWNER, aborted.uploadId()).state());
        log.info("decision: completed/aborted uploads unaffected by idle sweep after {} ms",
                IDLE_TTL * 10);
    }

    private long physicalBlobBytes(ObjectStoreService svc) throws Exception {
        java.lang.reflect.Field f = ObjectStoreService.class.getDeclaredField("contentStore");
        f.setAccessible(true);
        return ((ContentStore) f.get(svc)).usedBytes();
    }
}
