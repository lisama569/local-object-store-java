package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StorageConfig;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 测试基座：在临时目录上构建独立的存储栈。 */
final class TestSupport {

    private TestSupport() {}

    /** 可控时钟：初始为固定基准时间，测试中可显式推进，保证空闲判定确定性。 */
    static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000_000L);
        LongSupplier supplier() { return now::get; }
        long now() { return now.get(); }
        void advance(long deltaMillis) { now.addAndGet(deltaMillis); }
    }

    static ObjectStoreService newService(Path dir, long quotaBytes, long maxPartSize) {
        return newService(dir, quotaBytes, maxPartSize, 0, System::currentTimeMillis);
    }

    static ObjectStoreService newService(Path dir) {
        return newService(dir, 64L * 1024 * 1024, 8L * 1024 * 1024);
    }

    /**
     * @param idleTtlMillis 未完成上传连续空闲过期阈值；<=0 表示关闭过期
     * @param clock 时钟来源（测试用 FakeClock 或系统时钟）
     */
    static ObjectStoreService newService(Path dir, long quotaBytes, long maxPartSize,
                                         long idleTtlMillis, LongSupplier clock) {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dir.toString());
        config.setQuotaBytes(quotaBytes);
        config.setMaxPartSize(maxPartSize);
        config.setUploadTtlMillis(Math.max(idleTtlMillis, 1));
        config.setUploadIdleTtlMillis(idleTtlMillis);
        return new ObjectStoreService(config, new ContentStore(config), new MetadataStore(config), clock);
    }

    static byte[] bytes(String seed, int size) {
        byte[] data = new byte[size];
        byte[] s = seed.getBytes();
        for (int i = 0; i < size; i++) {
            data[i] = s[i % s.length];
        }
        return data;
    }
}
