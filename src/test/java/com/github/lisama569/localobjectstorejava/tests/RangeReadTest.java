package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 区间读取：合法区间与整读对齐，非法/越界原因可区分，权限与版本判定不被绕过。 */
class RangeReadTest {

    private static final Logger log = LoggerFactory.getLogger(RangeReadTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    private byte[] content() {
        // 0..99，便于人工核对区间
        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    private static byte[] slice(byte[] full, int start, int end) {
        byte[] out = new byte[end - start + 1];
        System.arraycopy(full, start, out, 0, out.length);
        return out;
    }

    @Test
    void rangesOfCurrentVersionMatchFullRead() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] data = content();
        svc.putObject(OWNER, "k", data, null);
        log.info("input: object of {} bytes, ranges [0,0],[0,9],[50,59],[99,99],[10,-1]", data.length);

        ObjectStoreService.RangeRead head = svc.getObjectRange(OWNER, "k", 0, 0);
        assertArrayEquals(slice(data, 0, 0), head.data());
        assertEquals(100, head.totalSize());
        assertEquals(0, head.start());
        assertEquals(0, head.end());

        assertArrayEquals(slice(data, 0, 9), svc.getObjectRange(OWNER, "k", 0, 9).data());
        assertArrayEquals(slice(data, 50, 59), svc.getObjectRange(OWNER, "k", 50, 59).data());
        assertArrayEquals(slice(data, 99, 99), svc.getObjectRange(OWNER, "k", 99, 99).data());
        ObjectStoreService.RangeRead tail = svc.getObjectRange(OWNER, "k", 10, -1);
        assertArrayEquals(slice(data, 10, 99), tail.data(), "end=-1 必须读到对象末尾");
        assertEquals(99, tail.end());
        log.info("decision: all legal ranges equal slices of full read; tail end resolved to {}", tail.end());
    }

    @Test
    void rangesWorkOnSpecificVersionAcrossVersions() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] v1 = content();                 // 100 字节
        ObjectVersion first = svc.putObject(OWNER, "k", v1, null);
        byte[] v2 = TestSupport.bytes("v2-", 40); // 当前版本 40 字节
        svc.putObject(OWNER, "k", v2, null);
        log.info("input: two versions, v1={} bytes current={} bytes", v1.length, v2.length);

        assertArrayEquals(slice(v1, 20, 39),
                svc.getObjectVersionRange(OWNER, "k", first.versionId(), 20, 39).data(),
                "指定版本区间必须读旧版本内容");
        assertArrayEquals(slice(v2, 0, 9),
                svc.getObjectRange(OWNER, "k", 0, 9).data(),
                "当前版本区间必须读新版本内容");
        // 区间按指定版本长度判定：20..39 对 40 字节当前版本合法，对……长度 40 同样合法；
        // 但 40..49 对 40 字节越界，对 100 字节旧版本合法
        assertArrayEquals(slice(v1, 40, 49),
                svc.getObjectVersionRange(OWNER, "k", first.versionId(), 40, 49).data());
        StoreException e = assertThrows(StoreException.class, () ->
                svc.getObjectRange(OWNER, "k", 40, 49));
        log.info("decision: same range on 40-byte current version code={}", e.code());
        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, e.code());
    }

    @Test
    void invalidRangesRejectedAsBadRequest() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "k", content(), null);

        assertBad(svc, -1, 10, "负 start");
        assertBad(svc, 10, 5, "end<start");
        assertBad(svc, 10, -2, "end 非 -1 的负数");
        log.info("decision: malformed ranges rejected as BAD_REQUEST");
    }

    private void assertBad(ObjectStoreService svc, long start, long end, String desc) {
        StoreException e = assertThrows(StoreException.class, () ->
                svc.getObjectRange(OWNER, "k", start, end));
        log.info("input: {} range=[{},{}] -> code={}", desc, start, end, e.code());
        assertEquals(StoreException.Code.BAD_REQUEST, e.code(), desc);
    }

    @Test
    void outOfBoundsRejectedAsRangeNotSatisfiable() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "k", content(), null);

        StoreException e1 = assertThrows(StoreException.class, () ->
                svc.getObjectRange(OWNER, "k", 100, 100));
        StoreException e2 = assertThrows(StoreException.class, () ->
                svc.getObjectRange(OWNER, "k", 0, 100));
        log.info("decision: out-of-bounds codes {} {}", e1.code(), e2.code());
        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, e1.code(), "start==size 越界");
        assertEquals(StoreException.Code.RANGE_NOT_SATISFIABLE, e2.code(), "end>=size 越界");
        assertEquals(100L, (Long) e1.details(), "越界错误应回带对象总长度，便于调用方修正");
    }

    @Test
    void rangeOnDeleteMarkerAndMissingObjectIsNotFound() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject(OWNER, "k", content(), null);
        svc.deleteObject(OWNER, "k", null);
        StoreException e = assertThrows(StoreException.class, () ->
                svc.getObjectRange(OWNER, "k", 0, 9));
        log.info("decision: range on delete-marker code={}", e.code());
        assertEquals(StoreException.Code.NOT_FOUND, e.code());
        assertThrows(StoreException.class, () -> svc.getObjectRange(OWNER, "absent", 0, 9));
    }

    @Test
    void rangeRespectsOwnerIsolationLikeFullRead() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject("alice", "secret", content(), null);
        // 越权区间读取必须与整读一致：NOT_FOUND，不暴露对象存在
        StoreException full = assertThrows(StoreException.class, () ->
                svc.getObject("bob", "secret"));
        StoreException ranged = assertThrows(StoreException.class, () ->
                svc.getObjectRange("bob", "secret", 0, 9));
        log.info("decision: unauthorized full={} range={}", full.code(), ranged.code());
        assertEquals(StoreException.Code.NOT_FOUND, full.code());
        assertEquals(StoreException.Code.NOT_FOUND, ranged.code(), "区间读取不得绕过访问控制");
    }

    @Test
    void multipartObjectRangesAlignWithFullAssembly() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] p1 = TestSupport.bytes("part-one-", 30);
        byte[] p2 = TestSupport.bytes("part-two-", 30);
        var up = svc.createUpload(OWNER, "mp");
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 2, p2, null);
        svc.completeUpload(OWNER, up.uploadId(), null);
        byte[] full = svc.getObject(OWNER, "mp");
        ObjectStoreService.RangeRead r = svc.getObjectRange(OWNER, "mp", 25, 40);
        assertArrayEquals(slice(full, 25, 40), r.data(), "跨分片边界区间必须与整读一致");
        log.info("decision: cross-part boundary [25,40] over 2 parts matches full read");
    }
}
