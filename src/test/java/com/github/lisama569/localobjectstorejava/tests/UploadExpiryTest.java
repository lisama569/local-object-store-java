package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.MetadataStore;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未完成上传过期：滑动续期、容量回收、并发续传/上报与回收的竞争。 */
class UploadExpiryTest {

    private static final Logger log = LoggerFactory.getLogger(UploadExpiryTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void idleUploadExpiresAndReleasesCapacity() throws Exception {
        TestSupport.Fixture f = TestSupport.fixtureWithTtl(dir, 500);
        ObjectStoreService svc = f.service();
        MultipartUpload up = svc.createUpload(OWNER, "ek1");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("idle-", 30), null);
        assertEquals(30, svc.usageOf(OWNER));
        assertEquals(30, svc.incompleteUploadBytesOf(OWNER));
        log.info("input: uploadId={} part1=30B, ttl=500ms, now idle", up.uploadId());

        Thread.sleep(700);
        int reclaimed = svc.sweepExpiredUploads();
        log.info("decision: sweep reclaimed={}, usage={}, physicalBytes={}",
                reclaimed, svc.usageOf(OWNER), f.contentStore().usedBytes());

        assertEquals(1, reclaimed);
        assertEquals(MultipartUpload.State.EXPIRED, svc.getUploadAnyState(up.uploadId()).state());
        assertEquals(0, svc.usageOf(OWNER), "过期必须真的释放逻辑占用");
        assertEquals(0, svc.incompleteUploadBytesOf(OWNER), "过期会话不再计入未完成上传占用");
        assertEquals(0, f.contentStore().usedBytes(), "过期必须物理释放内容块");
    }

    @Test
    void interactionsSlideExpiryAndKeepSessionAlive() throws Exception {
        TestSupport.Fixture f = TestSupport.fixtureWithTtl(dir, 200);
        ObjectStoreService svc = f.service();
        MultipartUpload up = svc.createUpload(OWNER, "ek2");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("keep-", 20), null);
        log.info("input: ttl=200ms, interactions every ~80ms");

        // 在阈值内持续查询/续传，每次都顺延过期时刻
        for (int i = 0; i < 3; i++) {
            Thread.sleep(80);
            svc.getUpload(OWNER, up.uploadId());
        }
        int reclaimed = svc.sweepExpiredUploads();
        log.info("decision: after sliding touches, sweep reclaimed={}", reclaimed);
        assertEquals(0, reclaimed, "持续交互不应被过期回收");

        // 停止交互后才真正过期
        Thread.sleep(300);
        assertEquals(1, svc.sweepExpiredUploads(), "连续空闲超过阈值才过期");
    }

    @Test
    void expiredUploadRejectsPartCompleteAndQuery() throws Exception {
        TestSupport.Fixture f = TestSupport.fixtureWithTtl(dir, 300);
        ObjectStoreService svc = f.service();
        MultipartUpload up = svc.createUpload(OWNER, "ek3");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("e-", 10), null);
        Thread.sleep(450);
        svc.sweepExpiredUploads();
        log.info("input: upload expired; client then queries/uploads/completes");

        StoreException query = assertThrows(StoreException.class,
                () -> svc.getUpload(OWNER, up.uploadId()));
        StoreException part = assertThrows(StoreException.class,
                () -> svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("e2-", 5), null));
        StoreException complete = assertThrows(StoreException.class,
                () -> svc.completeUpload(OWNER, up.uploadId(), null));
        log.info("decision: query={} part={} complete={}",
                query.code(), part.code(), complete.code());

        assertEquals(StoreException.Code.UPLOAD_EXPIRED, query.code());
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, part.code());
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, complete.code());
        assertEquals(0, svc.usageOf(OWNER), "拒绝的写入不得重新计入容量");
        assertEquals(0, f.contentStore().usedBytes());

        // 中止已过期会话按幂等成功处理，不报错
        svc.abortUpload(OWNER, up.uploadId());
    }

    @Test
    void racingPartAndSweepHasExactlyOneOutcome() throws Exception {
        final int threads = 12;
        for (int iter = 0; iter < 30; iter++) {
            final int iteration = iter;
            TestSupport.Fixture f = TestSupport.fixtureWithTtl(
                    dir.resolve("iter-" + iter), 3_600_000);
            ObjectStoreService svc = f.service();
            MultipartUpload up = svc.createUpload(OWNER, "race");
            // 上传第 1 片建立占用
            svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("base-", 50), null);
            long beforePhysical = f.contentStore().usedBytes();
            assertEquals(50, beforePhysical, "iter=" + iteration);

            final ObjectStoreService sv = svc;
            final String uploadId = up.uploadId();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger partOk = new AtomicInteger();
            AtomicInteger expiredRejections = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        byte[] data = TestSupport.bytes("racing-content-", 7);
                        sv.uploadPart(OWNER, uploadId, 2, data, null);
                        partOk.incrementAndGet();
                    } catch (StoreException e) {
                        // 合法结果只有两种：交互赢（成功）或回收赢（UPLOAD_EXPIRED）。
                        // 若某线程在临界时刻先成功写入、随后被同轮 sweep 回收，
                        // 它自身仍是“成功”，其余线程得到 EXPIRED——下面按终态统一校验。
                        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code(),
                                "iter=" + iteration + " 不允许出现成功/过期以外的结果: " + e.code());
                        expiredRejections.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            Future<?> sweeper = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sv.sweepExpiredUploads();
            });
            // 所有线程就位后，把会话推到“刚好过期”的临界点再同时开闸，
            // 使得 12 个续传请求与 1 个回收操作在同一时刻竞争元数据锁。
            sv.armExpiryRace(uploadId);
            start.countDown();
            sweeper.get(5, TimeUnit.SECONDS);
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            // 不再二次扫描：最终状态必须由 12 个交互与 1 个回收的单次竞争决出。
            long usage = svc.usageOf(OWNER);
            long physical = f.contentStore().usedBytes();
            MultipartUpload.State state = svc.getUploadAnyState(uploadId).state();
            log.info("iter={}: partOk={} expired={} finalState={} usage={} physical={}",
                    iter, partOk.get(), expiredRejections.get(), state, usage, physical);

            // 全局不变量：逻辑用量与物理占用永远相等，不存在重复计算/漏算/泄漏
            assertEquals(usage, physical,
                    "iter=" + iteration + " 逻辑用量必须与物理占用一致");
            boolean part2Survives = state == MultipartUpload.State.ACTIVE
                    && svc.getUploadAnyState(uploadId).parts().containsKey(2);
            if (part2Survives) {
                // 交互赢：会话被续上、2 号片保留，全部线程幂等成功，容量恰好两片
                assertEquals(threads, partOk.get(),
                        "iter=" + iteration + " 续传赢时所有线程必须幂等成功");
                assertEquals(0, expiredRejections.get(), "iter=" + iteration);
                assertEquals(57, usage, "iter=" + iteration + " 容量恰好为两片，无重复计算");
            } else {
                // 回收赢（含“临界线程先成功、随后被同轮回收”的交错）：
                // 2 号片未被保留，容量彻底归零，未成功的线程全部得到 EXPIRED
                assertEquals(MultipartUpload.State.EXPIRED, state, "iter=" + iteration);
                assertEquals(threads - partOk.get(), expiredRejections.get(),
                        "iter=" + iteration);
                assertEquals(0, usage, "iter=" + iteration + " 回收赢则容量必须归零，无泄漏");
                assertEquals(0, physical, "iter=" + iteration + " 物理块必须释放干净");
            }
            assertTrue(pool.isTerminated());
        }
    }

    @Test
    void incompleteUploadBytesTrackedAndConsistentWithQuota() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload a = svc.createUpload(OWNER, "ek6a");
        MultipartUpload b = svc.createUpload(OWNER, "ek6b");
        svc.uploadPart(OWNER, a.uploadId(), 1, TestSupport.bytes("a1-", 30), null);
        svc.uploadPart(OWNER, a.uploadId(), 2, TestSupport.bytes("a2-", 20), null);
        svc.uploadPart(OWNER, b.uploadId(), 1, TestSupport.bytes("b1-", 15), null);
        log.info("input: two active uploads with 50B and 15B");

        assertEquals(65, svc.incompleteUploadBytesOf(OWNER),
                "未完成上传占用 = 两个 ACTIVE 会话分片之和");
        assertEquals(65, svc.usageOf(OWNER), "尚无对象时，总用量应等于未完成占用");

        // 完成 a：50B 从“上传占用”转移为“对象占用”，总量不变
        svc.completeUpload(OWNER, a.uploadId(), null);
        assertEquals(15, svc.incompleteUploadBytesOf(OWNER));
        assertEquals(65, svc.usageOf(OWNER), "完成只做引用转移，总量口径不变");

        // 中止 b：15B 真正释放
        svc.abortUpload(OWNER, b.uploadId());
        assertEquals(0, svc.incompleteUploadBytesOf(OWNER));
        assertEquals(50, svc.usageOf(OWNER), "中止后只剩对象占用 50B");
        log.info("decision: after complete+abort, uploadBytes=0 usage=50 (object only)");
    }

    @Test
    void completedAndAbortedUploadsAreNeverSwept() throws Exception {
        TestSupport.Fixture f = TestSupport.fixtureWithTtl(dir, 500);
        ObjectStoreService svc = f.service();

        MultipartUpload done = svc.createUpload(OWNER, "ek5a");
        svc.uploadPart(OWNER, done.uploadId(), 1, TestSupport.bytes("ok-", 12), null);
        svc.completeUpload(OWNER, done.uploadId(), null);

        MultipartUpload aborted = svc.createUpload(OWNER, "ek5b");
        svc.uploadPart(OWNER, aborted.uploadId(), 1, TestSupport.bytes("ab-", 8), null);
        svc.abortUpload(OWNER, aborted.uploadId());

        Thread.sleep(700);
        int reclaimed = svc.sweepExpiredUploads();
        log.info("decision: sweep after ttl reclaimed={} (completed/aborted excluded)", reclaimed);
        assertEquals(0, reclaimed, "只回收 ACTIVE 会话");
        assertEquals(12, svc.usageOf(OWNER), "已完成对象的占用保持不变");
    }
}
