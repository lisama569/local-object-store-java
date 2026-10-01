package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StorageConfig;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.UploadExpiryReaper;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用可控时钟稳定覆盖“上报分片 / 过期回收刚好相撞”以及后台回收：
 * 每个请求要么成功续上、要么明确 UPLOAD_EXPIRED；逻辑用量始终等于物理占用；
 * 一次过期只回收一次；放弃后无任何请求也会释放；持续活跃的会话不被误回收。
 */
class UploadExpiryCollisionTest {

    private static final Logger log = LoggerFactory.getLogger(UploadExpiryCollisionTest.class);
    private static final String OWNER = "alice";
    private static final long TTL = 1000;

    @TempDir
    Path dir;

    /**
     * 关键复现：墙钟在一次 uploadPart 处理过程中跨过空闲阈值。
     * 旧实现先在 requireUsable 读一次时钟（判定没过期），随后在 checkQuota 的嵌套清扫中
     * 又读一次时钟（判定已过期并回收），但分片仍然写入并返回成功 —— 成功/过期中间态。
     * 修复后入口只判定一次：第一次采样时空闲恰好等于阈值 → 整次请求成功并续期；
     * 之后的处理即使再采样到阈值之后，也不能把自己这次成功请求变成“已过期”。
     */
    @Test
    void requestCrossingThresholdMidProcessingIsDeterministic() {
        long base = 1_000_000L;
        // create 读 1 次；seed 的 uploadPart 读 2 次（入口判定 + touch）；
        // crossing 的 uploadPart：入口第一次读到 base+3（seed 的 lastInteract=base+2，空闲恰=1ms==ttl，
        // 未过期），处理内若再读就是 base+4（空闲=2ms>ttl，旧代码的嵌套清扫会在这里误回收）。
        TestSupport.ScriptedClock clock = new TestSupport.ScriptedClock(
                base, base + 1, base + 2, base + 3, base + 4);
        ObjectStoreService svc = TestSupport.newService(dir, 1024 * 1024, 1024 * 1024, 1L, clock.supplier());

        MultipartUpload up = svc.createUpload(OWNER, "cross");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 10), null);
        log.info("input: first sample inside the racing request sees idle==ttl (alive), a second sample would see ttl+1 (expired)");

        // 修复后必须是确定的“成功并续期”，而不是“返回成功但会话已过期”
        svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("newpart-", 7), null);

        // 时钟此后冻结在 base+4：反复查询/统计都不会自行推进时间，断言判定不受干扰
        MultipartUpload after = svc.getUpload(OWNER, up.uploadId());
        log.info("decision: after crossing request state={} parts={} unfinishedBytes={} physicalBytes={}",
                after.state(), after.parts().keySet(),
                svc.unfinishedUploadBytes(OWNER), TestSupport.physicalUsedBytes(svc));
        assertEquals(MultipartUpload.State.ACTIVE, after.state(),
                "成功返回的请求必须把会话续上，不能事后变成 EXPIRED");
        assertEquals(2, after.parts().size(), "新分片必须真实挂在会话上");
        assertEquals(17, svc.unfinishedUploadBytes(OWNER), "新分片必须如实计入未完成占用");
        assertEquals(17, svc.usageOf(OWNER));
        assertEquals(17, TestSupport.physicalUsedBytes(svc),
                "逻辑用量必须等于物理占用：成功写入的块不能游离在计量之外");

        // 之后真正空闲超时：getUpload 已把 lastInteract 刷到 base+4，再推进 ttl+1 才过期
        clock.advance(2);
        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 3, TestSupport.bytes("late-", 5), null));
        log.info("decision: later upload after real idle code={}", e.code());
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
        assertEquals(0, svc.usageOf(OWNER));
        assertEquals(0, TestSupport.physicalUsedBytes(svc), "过期后物理块必须全部回收，无游离块");
    }

    /**
     * 8 线程同时在“边界（空闲==ttl）”上报分片：锁内串行化后必须全部成功续期，
     * 每个分片都计入且物理占用与逻辑用量一致。
     */
    @Test
    void concurrentPartsAtExactBoundaryAllWin() throws Exception {
        raceAtClockOffset(0L, "idle == ttl：全部请求必须成功续上");
    }

    /**
     * 8 线程同时在“已超阈值（空闲==ttl+1）”上报分片：先拿到锁的请求完成唯一一次回收，
     * 其余请求明确得到 UPLOAD_EXPIRED；不重复扣减、不漏回收、无游离块。
     */
    @Test
    void concurrentPartsPastBoundaryAllExpire() throws Exception {
        raceAtClockOffset(1L, "idle == ttl+1：必须全部明确过期且只回收一次");
    }

    /** 先在边界上并发成功、再推进到超时并发失败：两轮结果都确定，跨轮也不残留。 */
    @Test
    void concurrentWinThenExpireAcrossPhases() throws Exception {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024 * 1024, 1024 * 1024, TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "phases");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 20), null);
        long seedTime = clock.now();
        clock.set(seedTime + TTL); // 边界

        RaceResult r1 = fireRace(svc, up.uploadId(), 8, 100);
        log.info("decision phase1 (boundary): stored={} expired={}", r1.stored(), r1.expired());
        assertEquals(8, r1.stored());
        assertEquals(0, r1.expired());
        assertTrue(r1.unexpected().isEmpty(), r1.unexpected().toString());
        assertEquals(MultipartUpload.State.ACTIVE, svc.peekUploadState(OWNER, up.uploadId()));

        clock.set(clock.now() + TTL + 1); // 越过阈值
        RaceResult r2 = fireRace(svc, up.uploadId(), 8, 200);
        log.info("decision phase2 (past ttl): stored={} expired={}", r2.stored(), r2.expired());
        assertEquals(0, r2.stored());
        assertEquals(8, r2.expired());
        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, up.uploadId()));
        assertEquals(0, svc.usageOf(OWNER));
        assertEquals(0, svc.unfinishedUploadBytes(OWNER));
        assertEquals(0, TestSupport.physicalUsedBytes(svc),
                "两轮竞争后唯一一次回收：所有物理块（含第一轮分片）必须释放干净");
    }

    /**
     * 确定性“先成功后过期”：part1/part2 先成功，时钟越过阈值后，“后台回收”和剩余
     * 8 个上报同时竞争同一把锁。固定时钟下回收一旦先拿到锁，会话整体 EXPIRED：
     * 所有上报明确 410，此前已成功的 part1/part2 也必须在这一次过期中被恰好一次释放，
     * 不允许出现“请求成功但已过期 / 块游离 / 重复或漏回收”。
     * （上报整体抢先的对称结局由 {@code concurrentPartsAtExactBoundaryAllWin}
     * 与墙钟压测覆盖。）
     */
    @Test
    void earlierWinsReclaimedExactlyOnceWhenSweepWinsRace() throws Exception {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024 * 1024, 1024 * 1024, TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "mixed");
        // part1 先成功；随后时钟推到边界，part2 在“空闲恰好==ttl”时成功并续期
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 30), null);
        clock.set(clock.now() + TTL);
        svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("second-", 40), null);
        assertEquals(70, TestSupport.physicalUsedBytes(svc));
        log.info("input: parts 1,2 stored (70 bytes); advance past ttl, then race sweep vs parts 3..10");

        // 越过阈值：此刻“回收”和 8 个上报在同一把锁上竞争（固定时钟，首个持锁者决定全局结局）
        clock.set(clock.now() + TTL + 1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stored = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        List<StoreException.Code> unexpected = Collections.synchronizedList(new ArrayList<>());
        pool.submit(() -> {
            try { start.await(); svc.sweepAllExpired(); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        for (int i = 0; i < threads; i++) {
            final int partNo = i + 3;
            pool.submit(() -> {
                try {
                    start.await();
                    svc.uploadPart(OWNER, up.uploadId(), partNo,
                            TestSupport.bytes("mxp" + partNo + "-", 20 + partNo), null);
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
        log.info("decision: stored={} expired={} unexpected={} state={} usage={} physical={}",
                stored.get(), expired.get(), unexpected,
                svc.peekUploadState(OWNER, up.uploadId()),
                svc.usageOf(OWNER), TestSupport.physicalUsedBytes(svc));

        assertEquals(threads, stored.get() + expired.get(), "每个请求必须二选一");
        assertTrue(unexpected.isEmpty(), unexpected.toString());
        // 回收线程无额外延迟、固定时钟下必为它先持锁：全部明确过期
        assertEquals(0, stored.get(), "越过阈值后回收抢锁成功，不能再有成功请求复活会话");
        assertEquals(threads, expired.get());
        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, up.uploadId()));
        assertEquals(0, svc.usageOf(OWNER), "口径归零");
        assertEquals(0, svc.unfinishedUploadBytes(OWNER));
        assertEquals(0, TestSupport.physicalUsedBytes(svc),
                "先前成功的 70 字节也必须被这次过期唯一回收，不留游离块");

        // 再来一轮扫描/请求：已经回收过，不得重复扣减（底层 release 对未知块只告警），也不复活
        assertEquals(0, svc.sweepAllExpired());
        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 99, TestSupport.bytes("late-", 5), null));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
        assertEquals(0, TestSupport.physicalUsedBytes(svc));
    }

    private void raceAtClockOffset(long pastTtl, String label) throws Exception {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        ObjectStoreService svc = TestSupport.newService(dir, 1024 * 1024, 1024 * 1024, TTL, clock.supplier());
        MultipartUpload up = svc.createUpload(OWNER, "race-" + pastTtl);
        // 每个分片内容互不相同，避免去重使物理占用与逻辑计量不可比
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-" + pastTtl + "-", 30), null);
        clock.set(clock.now() + TTL + pastTtl);
        log.info("input: {} — 8 threads fire parts {}..{} at same clock={} (ttl={})",
                label, 2, 9, clock.now(), TTL);

        RaceResult r = fireRace(svc, up.uploadId(), 8, 2);
        log.info("decision: stored={} expired={} unexpected={} usage={} unfinished={} physical={}",
                r.stored(), r.expired(), r.unexpected(),
                safeUsage(svc), safeUnfinished(svc), TestSupport.physicalUsedBytes(svc));

        assertEquals(8, r.stored() + r.expired(), "每个请求要么成功续上要么明确过期");
        assertTrue(r.unexpected().isEmpty(), "不允许成功/过期以外的结果: " + r.unexpected());
        assertEquals(safeUsage(svc), TestSupport.physicalUsedBytes(svc),
                "逻辑用量必须等于物理占用");
        assertEquals(safeUnfinished(svc), TestSupport.physicalUsedBytes(svc),
                "未完成占用口径必须等于物理占用");
        if (pastTtl == 0) {
            assertEquals(8, r.stored(), label);
            // 30(seed) + 每个分片 9+partNo(2..9) = 30 + (11+12+13+14+15+16+17+18)=30+116=146
            assertEquals(146, TestSupport.physicalUsedBytes(svc));
            assertEquals(MultipartUpload.State.ACTIVE, svc.peekUploadState(OWNER, up.uploadId()));
        } else {
            assertEquals(0, r.stored(), label);
            assertEquals(8, r.expired(), label);
            assertEquals(0, TestSupport.physicalUsedBytes(svc), "只回收一次但要回收干净");
            assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, up.uploadId()));
        }
    }

    private record RaceResult(int stored, int expired, List<StoreException.Code> unexpected) {}

    private RaceResult fireRace(ObjectStoreService svc, String uploadId,
                                int threads, int seedTag) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stored = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        List<StoreException.Code> unexpected = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int partNo = i + 2;
            pool.submit(() -> {
                try {
                    start.await();
                    svc.uploadPart(OWNER, uploadId, partNo,
                            TestSupport.bytes("race" + seedTag + "-p" + partNo + "-", 9 + partNo), null);
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
        return new RaceResult(stored.get(), expired.get(), unexpected);
    }

    private long safeUsage(ObjectStoreService svc) {
        try { return svc.usageOf(OWNER); } catch (StoreException e) { return -1; }
    }

    private long safeUnfinished(ObjectStoreService svc) {
        try { return svc.unfinishedUploadBytes(OWNER); } catch (StoreException e) { return -1; }
    }

    /**
     * 真实墙钟压测：一个会话刚建好，8 个上报线程与一个“后台回收”线程同时启动，
     * 各线程随机睡眠，使得到达边界的先后不可预测。走向允许三种但都必须自洽：
     * 1) 全部请求成功 → 会话 ACTIVE，每个成功分片恰好一份物理块；
     * 2) 回收发生在全部请求之前 → 全部明确 UPLOAD_EXPIRED，物理块归零；
     * 3) 一部分请求先成功、之后被回收、其余明确过期 → 最终 EXPIRED，
     *    已成功的分片在那次过期中被“恰好一次”回收，同样不留游离块。
     * 任意时刻：请求结果只有成功/明确过期两种；逻辑用量 == 物理占用。
     */
    @Test
    void wallClockRaceBetweenPartsAndBackgroundSweep() throws Exception {
        long ttlMs = 20;
        int iterations = 15;
        int threads = 8;
        java.util.Random rnd = new java.util.Random(42);
        int[] outcomes = new int[3]; // [全成功, 先成功后过期, 全过期]
        for (int iter = 0; iter < iterations; iter++) {
            // 每轮独立存储目录：TTL 很短，不与其他轮次共享块/用量，便于独立断言“逻辑==物理”。
            StorageConfig config = new StorageConfig();
            config.setRootDir(dir.resolve("wall-" + iter).toString());
            config.setQuotaBytes(1024 * 1024);
            config.setMaxPartSize(1024 * 1024);
            config.setUploadTtlMillis(ttlMs);
            config.setUploadIdleTtlMillis(ttlMs);
            ObjectStoreService svc = new ObjectStoreService(config, new ContentStore(config),
                    new MetadataStore(config), System::currentTimeMillis);

            MultipartUpload up = svc.createUpload(OWNER, "wall");
            svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("wallseed-", 16), null);
            final String uploadId = up.uploadId();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger stored = new AtomicInteger();
            AtomicInteger expired = new AtomicInteger();
            List<StoreException.Code> unexpected = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
            // 后台回收线程：模拟定时器，在竞争窗口内扫多轮，制造全成功/先成功后过期/全过期三种走向
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 4; k++) {
                        Thread.sleep(rnd.nextInt(18));
                        svc.sweepAllExpired();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < threads; i++) {
                final int partNo = i + 2;
                pool.submit(() -> {
                    try {
                        start.await();
                        Thread.sleep(rnd.nextInt(25));
                        svc.uploadPart(OWNER, uploadId, partNo,
                                TestSupport.bytes("wallp" + partNo + "-", 12 + partNo), null);
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
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "iter=" + iter);
            assertTrue(unexpected.isEmpty(), "iter=" + iter + " 出现成功/过期以外的结果: " + unexpected);
            assertEquals(threads, stored.get() + expired.get(), "iter=" + iter + " 每个请求必须二选一");

            // 让本轮可能仍 ACTIVE 的会话也走到确定终态（等待超过 TTL 后再扫一次）
            Thread.sleep(ttlMs + 5);
            svc.sweepAllExpired();

            MultipartUpload.State state = svc.peekUploadState(OWNER, uploadId);
            long logical = svc.usageOf(OWNER);
            long unfinished = svc.unfinishedUploadBytes(OWNER);
            long physical = TestSupport.physicalUsedBytes(svc);
            if (stored.get() == threads) {
                outcomes[0]++;
            } else if (stored.get() == 0) {
                outcomes[2]++;
            } else {
                outcomes[1]++;
            }
            // 终态：只要有请求收到过期，会话就必须 EXPIRED；全成功的会话此刻也已因等待+扫描而过期
            assertEquals(MultipartUpload.State.EXPIRED, state, "iter=" + iter);
            assertEquals(0, physical, "iter=" + iter + " 终态物理块必须全部回收，无游离块");
            assertEquals(0, logical, "iter=" + iter + " 逻辑用量必须归零");
            assertEquals(0, unfinished, "iter=" + iter + " 未完成占用必须归零");
            log.info("input: wall-clock iter={} ttl={}ms | decision: stored={} expired={} -> state={}, all capacity reclaimed (logical={}, physical={})",
                    iter, ttlMs, stored.get(), expired.get(), state, logical, physical);
        }
        log.info("decision: outcome tally allWin={} winThenExpire={} allExpire={} across {} iterations",
                outcomes[0], outcomes[1], outcomes[2], iterations);
        assertEquals(iterations, outcomes[0] + outcomes[1] + outcomes[2]);
    }

    /** 调用方彻底放弃、再无任何请求：后台回收到期必须真正释放容量，且可重复扫描不重复扣减。 */
    @Test
    void backgroundReaperReleasesAbandonedUploadWithoutAnyRequest() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        var ctx = buildContext(dir, clock, 4096);
        ObjectStoreService svc = ctx.svc();
        UploadExpiryReaper reaper = ctx.reaper();

        MultipartUpload abandoned = svc.createUpload(OWNER, "abandoned");
        svc.uploadPart(OWNER, abandoned.uploadId(), 1, TestSupport.bytes("ghost-", 40), null);
        MultipartUpload alive = svc.createUpload(OWNER, "alive");
        svc.uploadPart(OWNER, alive.uploadId(), 1, TestSupport.bytes("keep-", 25), null);
        // 活着的会话持续有查询：把它的交互时间推到“当前”，放弃的会话停在过去
        clock.advance(TTL / 2);
        svc.getUpload(OWNER, alive.uploadId());
        clock.advance(TTL / 2 + 1); // 放弃者空闲 ttl+1；活跃者刚交互过
        log.info("input: abandoned upload idle={}ms, active upload idle<{}ms; drive background sweep with NO client request",
                TTL + 1, TTL);

        int reaped = svc.sweepAllExpired(); // 等价于定时器到点触发的 runSweepOnce
        reaper.runSweepOnce();              // 再来一轮：必须幂等、不重复回收

        log.info("decision: reaped={} abandonedState={} aliveState={} usage={} unfinished={} physical={}",
                reaped,
                svc.peekUploadState(OWNER, abandoned.uploadId()),
                svc.peekUploadState(OWNER, alive.uploadId()),
                svc.usageOf(OWNER), svc.unfinishedUploadBytes(OWNER),
                TestSupport.physicalUsedBytes(svc));
        assertEquals(1, reaped, "只有放弃的会话应被后台回收");
        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, abandoned.uploadId()));
        assertEquals(MultipartUpload.State.ACTIVE, svc.peekUploadState(OWNER, alive.uploadId()),
                "持续查询的会话不得被误判过期");
        assertEquals(25, svc.unfinishedUploadBytes(OWNER));
        assertEquals(25, svc.usageOf(OWNER));
        assertEquals(25, TestSupport.physicalUsedBytes(svc), "释放一个、保留一个，物理与逻辑一致");

        // 放弃者后续再发请求必须明确 410
        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, abandoned.uploadId(), 2, TestSupport.bytes("late-", 5), null));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
    }

    /** 后台回收的结果持久化：用同一目录重建存储栈，过期状态与已释放容量不复活。 */
    @Test
    void backgroundReaperResultSurvivesRestart() {
        TestSupport.FakeClock clock = new TestSupport.FakeClock();
        var ctx = buildContext(dir, clock, 4096);
        MultipartUpload up = ctx.svc().createUpload(OWNER, "restart");
        ctx.svc().uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("ghost-", 40), null);
        clock.advance(TTL + 1);
        assertEquals(1, ctx.svc().sweepAllExpired());
        log.info("input: reaped before restart, rebuild stores from same dir");

        ObjectStoreService restored = TestSupport.newService(dir, 4096, 4096, TTL, clock.supplier());
        StoreException e = assertThrows(StoreException.class, () ->
                restored.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("late-", 5), null));
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
        assertEquals(0, restored.usageOf(OWNER));
        assertEquals(0, TestSupport.physicalUsedBytes(restored), "物理块重启后仍缺失=已被真正删除");
        log.info("decision: expiry and released capacity persisted across restart");
    }

    /** 旧快照（没有 lastInteractAtMillis 字段）反序列化后回填创建时间，不会被立刻误回收。 */
    @Test
    void oldSnapshotWithoutInteractFieldIsNotImmediatelyExpired() throws Exception {
        long created = 5_000_000L;
        MultipartUpload u = new MultipartUpload("u1", OWNER, "legacy", created, created + TTL);
        Field f = MultipartUpload.class.getDeclaredField("lastInteractAtMillis");
        f.setAccessible(true);
        f.setLong(u, 0L); // 模拟旧数据反序列化后的默认值
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buf)) {
            out.writeObject(u);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buf.toByteArray()))) {
            MultipartUpload back = (MultipartUpload) in.readObject();
            log.info("decision: legacy upload lastInteract={} (createdAt={}) state={}",
                    back.lastInteractAtMillis(), back.createdAtMillis(), back.state());
            assertEquals(created, back.lastInteractAtMillis(), "旧会话必须回填创建时间而非 0");
            assertEquals(MultipartUpload.State.ACTIVE, back.state());
        }
    }

    private record Context(ObjectStoreService svc, UploadExpiryReaper reaper, StorageConfig config) {}

    private Context buildContext(Path dataDir, TestSupport.FakeClock clock, long quota) {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dataDir.toString());
        config.setQuotaBytes(quota);
        config.setMaxPartSize(quota);
        config.setUploadTtlMillis(Math.max(TTL, 1));
        config.setUploadIdleTtlMillis(TTL);
        ObjectStoreService svc = new ObjectStoreService(config, new ContentStore(config),
                new MetadataStore(config), clock.supplier());
        return new Context(svc, new UploadExpiryReaper(svc, config), config);
    }
}
