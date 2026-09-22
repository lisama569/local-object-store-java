package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本管理与并发覆盖：并发写同一对象不出现半写状态，删除作用范围明确。 */
class VersioningConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(VersioningConcurrencyTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void overwriteCreatesNewVersionAndOldVersionStillReadable() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] v1data = TestSupport.bytes("version-1-", 64);
        byte[] v2data = TestSupport.bytes("version-2-", 64);
        ObjectVersion v1 = svc.putObject(OWNER, "k", v1data, null);
        ObjectVersion v2 = svc.putObject(OWNER, "k", v2data, null);
        log.info("input: two sequential overwrites; v1={} v2={}", v1.versionId(), v2.versionId());

        assertArrayEquals(v2data, svc.getObject(OWNER, "k"), "当前版本必须是最后写入");
        assertArrayEquals(v1data, svc.getObjectVersion(OWNER, "k", v1.versionId()),
                "旧版本必须仍可读取");
        assertEquals(2, svc.listVersions(OWNER, "k").size());
    }

    @Test
    void concurrentOverwritesAllBecomeCompleteVersions() throws Exception {
        ObjectStoreService svc = TestSupport.newService(dir);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObjectVersion>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return svc.putObject(OWNER, "hot", TestSupport.bytes("writer-" + n + "-", 48), null);
            }));
        }
        start.countDown();
        Set<String> versionIds = new HashSet<>();
        for (Future<ObjectVersion> f : futures) {
            versionIds.add(f.get().versionId());
        }
        pool.shutdown();
        log.info("input: {} concurrent writers; distinct versions={}", writers, versionIds.size());

        List<ObjectVersion> history = svc.listVersions(OWNER, "hot");
        assertEquals(writers, versionIds.size(), "每次写入必须产生独立版本");
        assertEquals(writers, history.size(), "所有并发写入都必须成为完整版本");
        // 当前版本必须自洽：内容哈希与读取校验一致（readVersion 内部会重新校验）
        byte[] current = svc.getObject(OWNER, "hot");
        ObjectVersion head = history.get(history.size() - 1);
        assertEquals(head.size(), current.length, "当前版本必须是完整自洽的");
    }

    @Test
    void concurrentCompleteOfSameUploadYieldsSingleVersion() throws Exception {
        ObjectStoreService svc = TestSupport.newService(dir);
        var up = svc.createUpload(OWNER, "once");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("only-", 32), null);

        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObjectVersion>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return svc.completeUpload(OWNER, up.uploadId(), null);
            }));
        }
        start.countDown();
        Set<String> ids = new HashSet<>();
        for (Future<ObjectVersion> f : futures) {
            ids.add(f.get().versionId());
        }
        pool.shutdown();
        log.info("input: {} concurrent completes on one upload; distinct versions={}",
                attempts, ids.size());

        assertEquals(1, ids.size(), "并发完成同一会话必须收敛到同一版本");
        assertEquals(1, svc.listVersions(OWNER, "once").size());
    }

    @Test
    void deleteMarkerHidesCurrentButKeepsHistory() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] data = TestSupport.bytes("hist-", 40);
        ObjectVersion v1 = svc.putObject(OWNER, "k", data, null);
        svc.deleteObject(OWNER, "k", null);
        log.info("input: delete current version of 'k' (delete marker)");

        StoreException e = assertThrows(StoreException.class, () -> svc.getObject(OWNER, "k"));
        assertEquals(StoreException.Code.NOT_FOUND, e.code(), "删除标记后当前版本不可读");
        assertTrue(svc.listKeys(OWNER).isEmpty(), "删除标记后不出现在列表");
        assertArrayEquals(data, svc.getObjectVersion(OWNER, "k", v1.versionId()),
                "删除标记不得误删历史版本内容");
    }

    @Test
    void deleteSpecificVersionLeavesOthersUntouched() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] d1 = TestSupport.bytes("keep-1-", 40);
        byte[] d2 = TestSupport.bytes("drop-2-", 40);
        byte[] d3 = TestSupport.bytes("keep-3-", 40);
        ObjectVersion v1 = svc.putObject(OWNER, "k", d1, null);
        ObjectVersion v2 = svc.putObject(OWNER, "k", d2, null);
        ObjectVersion v3 = svc.putObject(OWNER, "k", d3, null);
        log.info("input: delete middle version {}", v2.versionId());

        svc.deleteObject(OWNER, "k", v2.versionId());

        assertEquals(2, svc.listVersions(OWNER, "k").size(), "只移除指定版本");
        assertArrayEquals(d1, svc.getObjectVersion(OWNER, "k", v1.versionId()));
        assertArrayEquals(d3, svc.getObject(OWNER, "k"), "当前版本回退为最近的存活版本");
        StoreException e = assertThrows(StoreException.class,
                () -> svc.getObjectVersion(OWNER, "k", v2.versionId()));
        assertEquals(StoreException.Code.NOT_FOUND, e.code());
        assertEquals(v3.versionId(), svc.listVersions(OWNER, "k").get(1).versionId());
    }
}
