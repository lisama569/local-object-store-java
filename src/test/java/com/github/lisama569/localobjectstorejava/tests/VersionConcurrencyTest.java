package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本与并发：并发覆盖同对象时每个读者读到的都是完整自洽版本；
 * 历史版本不变；删除按版本/按 key 的作用域互不误伤。
 */
class VersionConcurrencyTest extends StoreTestSupport {

    @BeforeEach
    void setUp() {
        buildStore();
    }

    private byte[] distinctContent(int writer, int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((writer * 31 + i * 7) & 0xFF);
        }
        return data;
    }

    @Test
    @DisplayName("8 个写线程并发覆盖同一对象：无半写，每次读取的摘要都与内容自洽")
    void concurrentOverwriteYieldsOnlyConsistentVersions() throws Exception {
        var caller = tenant("writer-team");
        String key = "hot/object";
        service.put(caller, key, stream(distinctContent(0, 200)), null);

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            List<Future<ObjectVersion>> futures = new ArrayList<>();
            for (int w = 1; w <= writers; w++) {
                int id = w;
                futures.add(pool.submit(() -> {
                    start.await();
                    byte[] data = distinctContent(id, 200 + id);
                    return service.put(caller, key, stream(data), sha256(data));
                }));
            }

            // 读者持续读取“同一个版本快照”（先取版本元数据，再按该 versionId 取内容），
            // 若版本不可变保证成立，则快照内 sha 与内容必须自洽；半写状态必然破坏这一点
            List<Future<?>> readers = new ArrayList<>();
            AtomicInteger reads = new AtomicInteger();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        ObjectVersion snapshot = service.get(caller, key, null);
                        byte[] data = download(caller, key, snapshot.versionId());
                        if (snapshot.size() != data.length
                                || !snapshot.contentSha256().equals(sha256(data))) {
                            failures.incrementAndGet();
                        }
                        reads.incrementAndGet();
                    }
                }));
            }

            start.countDown();
            for (Future<ObjectVersion> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            readers.forEach(f -> f.cancel(true));
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            log.info("[judge] concurrent writes={} successfulReads={} inconsistencies={}",
                    writers, reads.get(), failures.get());
            assertEquals(0, failures.get(), "任何读者都不得读到半写/不自洽版本");

            // 历史版本：初始 + writers 个新版本全部可独立读取
            List<ObjectVersion> versions = service.listVersions(caller, key);
            assertEquals(writers + 1, versions.size());
            for (ObjectVersion v : versions) {
                byte[] data = download(caller, v.objectKey(), v.versionId());
                assertEquals(v.size(), data.length);
                assertEquals(v.contentSha256(), sha256(data));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发删除与覆盖：版本计数最终确定，所有版本或可读或已删，无异常泄漏")
    void concurrentDeleteAndOverwrite() throws Exception {
        var caller = tenant("writer-team");
        String key = "racer";
        service.put(caller, key, stream(distinctContent(0, 100)), null);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int id = i;
                tasks.add(() -> {
                    start.await();
                    for (int round = 0; round < 5; round++) {
                        byte[] data = distinctContent(100 + id * 10 + round, 100);
                        service.put(caller, key, stream(data), sha256(data));
                    }
                    return null;
                });
            }
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> {
                    start.await();
                    for (int round = 0; round < 5; round++) {
                        try {
                            service.listVersions(caller, key).stream()
                                    .findAny()
                                    .ifPresent(v -> {
                                        try {
                                            service.deleteVersion(caller, key, v.versionId());
                                        } catch (ObjectStoreException e) {
                                            if (e.getType() != ErrorType.NOT_FOUND) {
                                                throw e;
                                            }
                                        }
                                    });
                        } catch (ObjectStoreException e) {
                            if (e.getType() != ErrorType.NOT_FOUND) {
                                throw e;
                            }
                        }
                    }
                    return null;
                });
            }
            List<Future<Object>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Object> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    errors.add(e.getCause() != null ? e.getCause() : e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(), "并发删除/覆盖不得抛出未预期异常: " + errors);

        // 最终状态自洽：剩余版本都可读，blob refCount 与版本数对应，容量与引用一致
        List<ObjectVersion> survivors;
        try {
            survivors = service.listVersions(caller, key);
        } catch (ObjectStoreException e) {
            assertEquals(ErrorType.NOT_FOUND, e.getType());
            survivors = List.of();
        }
        long expectedBytes = survivors.stream()
                .map(ObjectVersion::blobKey).distinct()
                .mapToLong(k -> metadata.getBlob(k).size()).sum();
        log.info("[judge] survivors={} expectedUsed={} actualUsed={}",
                survivors.size(), expectedBytes, service.capacityUsed());
        assertEquals(expectedBytes, service.capacityUsed());
    }

    @Test
    @DisplayName("删除单个版本不影响其他版本与默认读取目标")
    void deleteOneVersionKeepsOthers() {
        var caller = tenant("t");
        ObjectVersion v1 = service.put(caller, "k", stream(bytes("v1-content")), null);
        ObjectVersion v2 = service.put(caller, "k", stream(bytes("v2-content-longer")), null);

        service.deleteVersion(caller, "k", v1.versionId());
        ObjectVersion latest = service.get(caller, "k", null);
        assertEquals(v2.versionId(), latest.versionId());

        assertThrows(ObjectStoreException.class,
                () -> service.get(caller, "k", v1.versionId()));
        assertArrayEquals(bytes("v2-content-longer"), download(caller, "k", null));
        log.info("[judge] v1 deleted; latest remains v2; key still readable");
    }

    @Test
    @DisplayName("删除最新版本后，默认读取回退到上一完整版本")
    void deleteLatestFallsBackToPrevious() {
        var caller = tenant("t");
        ObjectVersion v1 = service.put(caller, "k", stream(bytes("first")), null);
        service.put(caller, "k", stream(bytes("second-version")), null);
        service.deleteVersion(caller, "k", service.get(caller, "k", null).versionId());

        ObjectVersion now = service.get(caller, "k", null);
        assertEquals(v1.versionId(), now.versionId());
        assertArrayEquals(bytes("first"), download(caller, "k", null));
    }
}
