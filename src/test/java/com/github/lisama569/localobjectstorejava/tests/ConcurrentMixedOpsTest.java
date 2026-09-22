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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多客户端并发上传、完成与删除相同及相关对象，结果必须一致可解释。 */
class ConcurrentMixedOpsTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentMixedOpsTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void concurrentPutAndDeleteOnSameKeyConverges() throws Exception {
        ObjectStoreService svc = TestSupport.newService(dir);
        int clients = 10;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger putOk = new AtomicInteger();
        AtomicInteger delOk = new AtomicInteger();
        AtomicInteger delNotFound = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                if (n % 2 == 0) {
                    svc.putObject(OWNER, "race", TestSupport.bytes("w" + n + "-", 32), null);
                    putOk.incrementAndGet();
                } else {
                    try {
                        svc.deleteObject(OWNER, "race", null);
                        delOk.incrementAndGet();
                    } catch (StoreException e) {
                        assertEquals(StoreException.Code.NOT_FOUND, e.code(),
                                "删除失败只允许 NOT_FOUND 一种原因");
                        delNotFound.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        log.info("input: {} clients racing put/delete on one key; putOk={} delOk={} delNotFound={}",
                clients, putOk.get(), delOk.get(), delNotFound.get());

        // 一致性判定：版本数 = 成功 put 数 + 成功删除标记数；读操作要么得到完整版本要么 NOT_FOUND
        List<ObjectVersion> history = svc.listVersions(OWNER, "race");
        assertEquals(putOk.get() + delOk.get(), history.size(),
                "每次成功的写/删都必须恰好留下一个版本记录");
        ObjectVersion head = history.get(history.size() - 1);
        if (head.deleteMarker()) {
            StoreException e = org.junit.jupiter.api.Assertions.assertThrows(StoreException.class,
                    () -> svc.getObject(OWNER, "race"));
            assertEquals(StoreException.Code.NOT_FOUND, e.code());
        } else {
            byte[] data = svc.getObject(OWNER, "race");
            assertEquals(head.size(), data.length, "读取必须看到完整自洽的版本");
        }
        log.info("decision: head deleteMarker={} versions={}", head.deleteMarker(), history.size());
    }

    @Test
    void concurrentUploadsToDistinctKeysAllSucceed() throws Exception {
        ObjectStoreService svc = TestSupport.newService(dir);
        int clients = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObjectVersion>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                var up = svc.createUpload(OWNER, "obj-" + n);
                svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("p2-" + n + "-", 24), null);
                svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("p1-" + n + "-", 24), null);
                return svc.completeUpload(OWNER, up.uploadId(), null);
            }));
        }
        start.countDown();
        int done = 0;
        for (Future<ObjectVersion> f : futures) {
            f.get();
            done++;
        }
        pool.shutdown();
        log.info("input: {} clients uploading distinct keys; completed={}", clients, done);

        assertEquals(clients, svc.listKeys(OWNER).size(), "相关但不同的对象不得互相干扰");
        for (int i = 0; i < clients; i++) {
            assertEquals(48, svc.getObject(OWNER, "obj-" + i).length);
        }
        assertTrue(svc.usageOf(OWNER) == clients * 48L, "用量必须等于全部对象字节和");
    }
}
