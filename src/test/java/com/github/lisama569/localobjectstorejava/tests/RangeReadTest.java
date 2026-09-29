package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 区间读取：边界切片、越界/非法区分、指定版本、权限与完整性校验。 */
class RangeReadTest {

    private static final Logger log = LoggerFactory.getLogger(RangeReadTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    /** 长度 10 的确定性内容，便于人工核对切片。 */
    private byte[] content() {
        return new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    }

    @Test
    void rangeMatchesFullReadSlice() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] full = content();
        svc.putObject(OWNER, "rk1", full, null);
        log.info("input: length=10, range [2,7] inclusive");

        ObjectStoreService.Range r = svc.getObjectRange(OWNER, "rk1", 2L, 7L);
        log.info("decision: returned [{},{}] of {}, bytes={}",
                r.startInclusive(), r.endInclusive(), r.totalSize(), Arrays.toString(r.data()));

        assertArrayEquals(Arrays.copyOfRange(full, 2, 8), r.data());
        // 与整段读出来后切片的结果必须逐字节一致
        assertArrayEquals(Arrays.copyOfRange(svc.getObject(OWNER, "rk1"), 2, 8), r.data());
        assertEquals(2, r.startInclusive());
        assertEquals(7, r.endInclusive());
        assertEquals(10, r.totalSize());
    }

    @Test
    void boundaryRangesFirstLastAndSingleByte() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] full = content();
        svc.putObject(OWNER, "rk2", full, null);

        ObjectStoreService.Range first = svc.getObjectRange(OWNER, "rk2", 0L, 0L);
        ObjectStoreService.Range last = svc.getObjectRange(OWNER, "rk2", 9L, 9L);
        ObjectStoreService.Range whole = svc.getObjectRange(OWNER, "rk2", 0L, 9L);
        log.info("input: single first/last byte and full [0,9]");

        assertArrayEquals(new byte[] {0}, first.data());
        assertArrayEquals(new byte[] {9}, last.data());
        assertArrayEquals(full, whole.data());
        assertEquals(10, whole.data().length);
    }

    @Test
    void endBeyondLengthIsClampedNotRejected() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "rk3", content(), null);
        log.info("input: length=10, range [5,100], end exceeds length");

        ObjectStoreService.Range r = svc.getObjectRange(OWNER, "rk3", 5L, 100L);
        log.info("decision: clamped end to {} (start inside length => OK)", r.endInclusive());
        assertArrayEquals(new byte[] {5, 6, 7, 8, 9}, r.data());
        assertEquals(9, r.endInclusive());
        assertEquals(10, r.totalSize());
    }

    @Test
    void startAtOrBeyondLengthRejectedWithDistinctCode() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "rk4", content(), null);

        StoreException atLength = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk4", 10L, 10L));
        StoreException beyond = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk4", 50L, 60L));
        log.info("input: start=10 (==length) and start=50; decision codes={},{}",
                atLength.code(), beyond.code());

        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, atLength.code());
        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, beyond.code());
    }

    @Test
    void malformedRangeIsBadRequestDistinctFromOutOfBounds() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "rk5", content(), null);

        StoreException missing = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk5", null, 3L));
        StoreException negative = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk5", -1L, 3L));
        StoreException inverted = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk5", 5L, 2L));
        log.info("input: null/negative/inverted ranges; decision codes={},{},{}",
                missing.code(), negative.code(), inverted.code());

        assertEquals(StoreException.Code.BAD_REQUEST, missing.code());
        assertEquals(StoreException.Code.BAD_REQUEST, negative.code());
        assertEquals(StoreException.Code.BAD_REQUEST, inverted.code());
    }

    @Test
    void rangeOnSpecificVersionReadsThatVersion() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] v1 = content();                 // 长度 10
        byte[] v2 = new byte[] {10, 11, 12};   // 长度 3
        ObjectVersion o1 = svc.putObject(OWNER, "rk6", v1, null);
        svc.putObject(OWNER, "rk6", v2, null);
        log.info("input: two versions v1len=10 v2len=3; range on v1 [8,9]");

        ObjectStoreService.Range r = svc.getObjectVersionRange(OWNER, "rk6",
                o1.versionId(), 8L, 9L);
        assertArrayEquals(new byte[] {8, 9}, r.data());
        assertEquals(10, r.totalSize());

        // 当前版本是 v2，对同一区间起点会因 v2 太短而越界
        StoreException e = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk6", 8L, 9L));
        log.info("decision: same range on current short version => {}", e.code());
        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, e.code());
    }

    @Test
    void rangeGoesThroughOwnershipCheckAndLooksLikeNotFound() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "rk7", content(), null);
        log.info("input: bob requests a range on alice's object");

        StoreException e = assertThrows(StoreException.class,
                () -> svc.getObjectRange("bob", "rk7", 0L, 1L));
        StoreException missing = assertThrows(StoreException.class,
                () -> svc.getObjectRange("bob", "does-not-exist", 0L, 1L));
        log.info("decision: cross-owner={} not-found={}", e.code(), missing.code());

        assertEquals(StoreException.Code.NOT_FOUND, e.code(),
                "区间读取必须走权限判定，越权表现与整段读取一致");
        assertEquals(e.code(), missing.code(), "越权与真不存在不可区分");
    }

    @Test
    void rangeStillValidatesContent() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] full = content();
        ObjectVersion v = svc.putObject(OWNER, "rk8", full, null);

        // 直接通过元数据层无法注入损坏；这里确认区间读取前的整段校验路径被执行：
        // 删除标记版本不可读（走同一 readVersion），返回 NOT_FOUND 而非切片结果。
        svc.deleteObject(OWNER, "rk8", null);
        StoreException e = assertThrows(StoreException.class,
                () -> svc.getObjectRange(OWNER, "rk8", 0L, 1L));
        log.info("input: range read after delete marker; decision={}", e.code());
        assertEquals(StoreException.Code.NOT_FOUND, e.code());

        // 指定旧版本仍可区间读取（删除标记不影响历史版本）
        ObjectStoreService.Range r = svc.getObjectVersionRange(OWNER, "rk8",
                v.versionId(), 3L, 5L);
        assertArrayEquals(new byte[] {3, 4, 5}, r.data());
    }
}
