package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期回收与续传/上报/后台清扫相撞时的确定性与容量一致性测试（可控时钟）。
 *
 * <p>覆盖两类真实交错：
 * <ol>
 *   <li>持锁处理请求期间时钟恰好跨过 TTL 边界（旧实现的嵌套清扫会把当前会话自己
 *       标记过期，却继续写入分片并返回成功 → 成功+过期+孤儿块）；</li>
 *   <li>上报分片与后台清扫真正并发撞锁（真实后台线程 + FakeClock），每个请求要么
 *       成功续上、要么明确 UPLOAD_EXPIRED，且逻辑用量恒等于物理占用。</li>
 * </ol>
 */
class ExpiryCollisionTest {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCollisionTest.class);
    private static final String OWNER = "alice";
    private static final long IDLE_TTL = 1000;

    @TempDir
    Path dir;

    /**
     * 复现旧缺陷：请求拿到锁、做完状态判定之后，时钟在同一持锁区间跨过 TTL。
     * 旧实现随后在 checkQuota 内部嵌套清扫，把“自己这个会话”标记 EXPIRED，
     * 但仍写入分片并返回成功：响应成功、会话已过期、用量为零、物理块永不回收。
     * 修复后：过期判定只发生在进锁入口的清扫，持锁段内不再读时钟，请求必须成功续上。
     */
    @Test
    void clockCrossingTtlWithinRequestMustNotOrphanAcceptedPart() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ObjectStoreService svc = newStack(dir, IDLE_TTL, now::get);
        MultipartUpload up = svc.createUpload(OWNER, "k-cross");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 30), null);
        // 把时钟推到“再读一次时钟就严格超过 TTL”的边界
        now.addAndGet(IDLE_TTL + 1);
        log.info("input: clock already past ttl by 1ms at lock entry; request must decide expired, "
                + "never succeed onto an expired session");

        // 越过阈值后进锁：入口清扫必须先把会话判定为过期
        StoreException e = expectExpired(() ->
                svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("late-", 11), null));
        log.info("decision: late part rejected code={} (no orphan write)", e.code());

        assertEquals(0, svc.usageOf(OWNER), "逻辑用量必须归零");
        assertEquals(0L, physicalBytes(svc), "过期会话的 seed 块必须被物理回收，不得留孤儿块");
        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, up.uploadId()));
    }

    /**
     * 核心对撞压测：多个上报线程与真实后台清扫线程在同一把元数据锁上反复相撞。
     * 时钟由测试在屏障处分阶段推进，覆盖“恰好边界（==ttl）成功续上”和
     * “严格超过（ttl+1）明确过期”两种判定，每轮结束都断言：
     * 每个请求只有成功或 UPLOAD_EXPIRED 两种结果；逻辑用量 == 物理占用。
     */
    @Test
    void concurrentPartsVsSweepAreDeterministicAndAccountingMatchesPhysical() throws Exception {
        for (int round = 1; round <= 30; round++) {
            runOneCollisionRound(round);
        }
    }

    private void runOneCollisionRound(int round) throws Exception {
        Path roundDir = dir.resolve("round-" + round);
        AtomicLong now = new AtomicLong(1_000_000L);
        ObjectStoreService svc = newStack(roundDir, IDLE_TTL, now::get);
        MultipartUpload up = svc.createUpload(OWNER, "k-race");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("seed-", 20), null);

        int uploaders = 6;
        int sweepers = 2;
        int participants = uploaders + sweepers;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(participants);
        AtomicInteger stored = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger swept = new AtomicInteger();
        List<StoreException.Code> unexpected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < uploaders; i++) {
            final int partNo = i + 2;
            pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    svc.uploadPart(OWNER, up.uploadId(), partNo,
                            TestSupport.bytes("r" + round + "-p" + partNo + "-", 9 + partNo), null);
                    stored.incrementAndGet();
                } catch (StoreException se) {
                    if (se.code() == StoreException.Code.UPLOAD_EXPIRED) {
                        expired.incrementAndGet();
                    } else {
                        unexpected.add(se.code());
                    }
                }
            });
        }
        for (int i = 0; i < sweepers; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                swept.addAndGet(svc.sweepAllExpired());
            });
        }
        // 等所有工作线程真正就位后，再在它们抢锁前推进时钟，保证“上报与清扫刚好相撞”
        assertTrue(ready.await(10, TimeUnit.SECONDS), "round " + round + " 工作线程未就位");
        boolean crossBeforeAcquire = round % 2 == 0;
        if (crossBeforeAcquire) {
            now.addAndGet(IDLE_TTL + 1);
        } else {
            now.addAndGet(IDLE_TTL); // 恰好等于阈值：不算“严格超过”，请求应成功续上
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "round " + round + " 线程未结束");

        long logical = svc.usageOf(OWNER);
        long unfinished = svc.unfinishedUploadBytes(OWNER);
        long physical = physicalBytes(svc);
        MultipartUpload.State state = svc.peekUploadState(OWNER, up.uploadId());
        log.info("round={} input advance={} => stored={} expired={} swept={}; "
                        + "state={} logicalUsage={} unfinished={} physical={}",
                round, crossBeforeAcquire ? "ttl+1(expired)" : "ttl(alive)",
                stored.get(), expired.get(), swept.get(), state, logical, unfinished, physical);

        assertTrue(unexpected.isEmpty(), "round " + round + " 出现非预期失败: " + unexpected);
        assertEquals(uploaders, stored.get() + expired.get(),
                "round " + round + " 每个请求要么成功要么明确过期");
        assertEquals(logical, physical,
                "round " + round + " 逻辑用量必须恒等于物理占用（无孤儿块/无重复扣减）");
        assertEquals(unfinished, logical, "round " + round + " 未完成口径与总用量一致（无对象版本）");

        if (crossBeforeAcquire) {
            // 严格超过：先抢到锁的无论是清扫线程还是上报线程，入口清扫都会把会话过期
            assertEquals(0, stored.get(), "round " + round + " 越过阈值后不得有请求成功复活会话");
            assertEquals(uploaders, expired.get(), "round " + round + " 所有请求必须明确过期");
            assertEquals(0L, physical, "round " + round + " 物理块必须全部回收");
            assertEquals(MultipartUpload.State.EXPIRED, state);
        } else {
            // 恰好边界：不判过期；首个上报成功并续期后，后来者全部在 TTL 窗口内成功
            assertEquals(uploaders, stored.get(), "round " + round + " 边界（==ttl）请求应全部成功续上");
            assertEquals(0, expired.get(), "round " + round + " 边界不得判过期");
            // part1=20；part n 大小 9+n，n=2..7 => 11+12+13+14+15+16=81；合计 101
            assertEquals(101L, physical, "round " + round + " 成功分片必须如实计入物理占用");
            assertEquals(MultipartUpload.State.ACTIVE, state);
        }
    }

    /**
     * 调用方彻底放弃、再也不发任何请求：后台清扫到期仍必须真正释放容量；
     * 持续续传/上报/查询的会话不得被误判；TTL 关闭时清扫为空操作。
     */
    @Test
    void abandonedUploadReapedWithoutFurtherRequestsAndActiveOneUntouched() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ObjectStoreService svc = newStack(dir, IDLE_TTL, now::get);
        MultipartUpload abandoned = svc.createUpload(OWNER, "gone");
        svc.uploadPart(OWNER, abandoned.uploadId(), 1, TestSupport.bytes("gone-", 40), null);
        MultipartUpload active = svc.createUpload(OWNER, "alive");
        svc.uploadPart(OWNER, active.uploadId(), 1, TestSupport.bytes("alive-", 12), null);
        log.info("input: two ACTIVE uploads (40 + 12 bytes), no further client requests planned");

        // 活跃会话每次间隔小于 TTL 续传新分片（滑动续期）；放弃的会话不再有任何请求。
        // 注意：续传只触及活跃会话，但惰性清扫仍会顺带回收同 owner 下已超时的放弃会话，
        // 因此“放弃会话在有别的请求时被回收”与“完全无请求时由后台回收”两条路径都覆盖到。
        boolean abandonedReaped = false;
        for (int i = 0; i < 4; i++) {
            now.addAndGet(IDLE_TTL - 100);
            svc.uploadPart(OWNER, active.uploadId(), i + 2,
                    TestSupport.bytes("tick" + i + "-", 4 + i), null); // 活跃会话持续上报
            int reaped = svc.sweepAllExpired();
            if (reaped > 0 || svc.peekUploadState(OWNER, abandoned.uploadId())
                    == MultipartUpload.State.EXPIRED) {
                abandonedReaped = true;
            }
            log.info("tick {} advanced {}ms, background sweep reaped={}, abandoned state={}",
                    i, IDLE_TTL - 100, reaped,
                    svc.peekUploadState(OWNER, abandoned.uploadId()));
            assertEquals(MultipartUpload.State.ACTIVE,
                    svc.peekUploadState(OWNER, active.uploadId()),
                    "持续上报的会话在第 " + i + " 个 tick 不能被误回收");
            if (abandonedReaped) {
                assertEquals(0, svc.sweepAllExpired(), "已回收会话不得被重复回收");
            }
        }
        assertTrue(abandonedReaped, "放弃的会话必须在空闲严格超过 TTL 后被回收（惰性或后台）");
        assertEquals(MultipartUpload.State.ACTIVE,
                svc.peekUploadState(OWNER, active.uploadId()), "持续上报的会话必须存活");
        // 放弃会话的 40 字节块已释放；活跃会话：part1=12, part2..5 大小 4..7 合计 12+4+5+6+7=34
        assertEquals(34L, physicalBytes(svc), "放弃会话的块必须释放，物理占用只含活跃会话");

        // 此后不再有任何前台请求（连查询也停止），时间越过 TTL，仅靠后台清扫
        now.addAndGet(IDLE_TTL + 1);
        int reaped = svc.sweepAllExpired();
        log.info("decision: after ttl+{}ms with zero requests, background sweep reaped={}",
                IDLE_TTL + 1, reaped);
        assertEquals(1, reaped, "停止交互的活跃会话也应在无请求情况下被后台回收");
        assertEquals(0L, svc.usageOf(OWNER));
        assertEquals(0L, svc.unfinishedUploadBytes(OWNER));
        assertEquals(0L, physicalBytes(svc), "无后续请求也必须真正释放物理占用");

        // 再扫一次必须是空操作：一次过期只回收一次，不重复扣减
        int again = svc.sweepAllExpired();
        assertEquals(0, again, "重复清扫不得重复回收");
        assertEquals(0L, physicalBytes(svc));
    }

    /**
     * 最严格的无请求回收：客户端上传一个分片后彻底消失，之后除了后台清扫不发生任何
     * 前台调用。到期容量必须由后台清扫独立释放，且重复清扫幂等。
     */
    @Test
    void purelyBackgroundSweepReleasesAbandonedUploadWithZeroRequests() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ObjectStoreService svc = newStack(dir, IDLE_TTL, now::get);
        MultipartUpload gone = svc.createUpload(OWNER, "ghost");
        svc.uploadPart(OWNER, gone.uploadId(), 1, TestSupport.bytes("ghost-", 37), null);
        log.info("input: upload {} holds 37 bytes, client will make NO further requests", gone.uploadId());

        // 未到阈值：后台清扫空转
        now.addAndGet(IDLE_TTL);
        assertEquals(0, svc.sweepAllExpired(), "空闲恰好等于阈值不得回收");
        assertEquals(37L, svc.usageOf(OWNER), "阈值边界容量仍计入");
        assertEquals(37L, physicalBytes(svc));

        now.addAndGet(1);
        int reaped = svc.sweepAllExpired(); // 唯一发生的“活动”，无任何前台请求
        log.info("decision: ttl+1ms reached with zero requests, pure background sweep reaped={}", reaped);
        assertEquals(1, reaped);
        assertEquals(0L, svc.usageOf(OWNER));
        assertEquals(0L, svc.unfinishedUploadBytes(OWNER));
        assertEquals(0L, physicalBytes(svc), "物理块必须随后台回收真正删除");
        assertEquals(MultipartUpload.State.EXPIRED, svc.peekUploadState(OWNER, gone.uploadId()));

        assertEquals(0, svc.sweepAllExpired(), "重复后台清扫幂等，不重复扣减");
        assertEquals(0L, physicalBytes(svc));
    }

    /** TTL 关闭（<=0）时任何清扫都不得改变会话状态或容量。 */
    @Test
    void sweepIsNoOpWhenTtlDisabled() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ObjectStoreService svc = newStack(dir, 0, now::get);
        MultipartUpload up = svc.createUpload(OWNER, "forever");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("keep-", 15), null);
        now.addAndGet(10L * IDLE_TTL);
        assertEquals(0, svc.sweepAllExpired(), "TTL 关闭时后台清扫必须为空操作");
        assertEquals(MultipartUpload.State.ACTIVE, svc.peekUploadState(OWNER, up.uploadId()));
        assertEquals(15L, svc.usageOf(OWNER));
        assertEquals(15L, physicalBytes(svc));
        log.info("decision: ttl disabled, sweep kept {} ACTIVE and {} bytes", up.uploadId(), 15);
    }

    // ---- helpers ----

    private ObjectStoreService newStack(Path root, long idleTtl, LongSupplier clock) {
        StorageConfigForTest config = new StorageConfigForTest(root, idleTtl);
        ContentStore content = new ContentStore(config);
        MetadataStore meta = new MetadataStore(config);
        return new ObjectStoreService(config, content, meta, clock);
    }

    private long physicalBytes(ObjectStoreService svc) {
        try {
            java.lang.reflect.Field f = ObjectStoreService.class.getDeclaredField("contentStore");
            f.setAccessible(true);
            return ((ContentStore) f.get(svc)).usedBytes();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private StoreException expectExpired(org.junit.jupiter.api.function.Executable action) {
        StoreException e = org.junit.jupiter.api.Assertions.assertThrows(StoreException.class, action);
        assertEquals(StoreException.Code.UPLOAD_EXPIRED, e.code());
        return e;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** 测试专用配置：TestSupport 的默认 maxParts 等保持与生产一致，仅覆盖根目录/TTL。 */
    private static final class StorageConfigForTest extends com.github.lisama569.localobjectstorejava.store.StorageConfig {
        StorageConfigForTest(Path root, long idleTtl) {
            setRootDir(root.toString());
            setQuotaBytes(4096);
            setMaxPartSize(1024);
            setUploadTtlMillis(Math.max(idleTtl, 1));
            setUploadIdleTtlMillis(idleTtl);
        }
    }
}
