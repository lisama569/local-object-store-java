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
        void set(long millis) { now.set(millis); }
    }

    /**
     * 脚本化时钟：前 N 次读取依次返回预设的时间点（用来精确制造“同一次请求内两次采样
     * 跨阈值”的局面），脚本耗尽后冻结在最后一个值，后续可再用 {@link #set}/{@link #advance}
     * 控制；冻结期间反复查询不会自行推进时间，避免断言本身扰动过期判定。
     */
    static final class ScriptedClock {
        private final AtomicLong frozen = new AtomicLong();
        private final java.util.concurrent.ConcurrentLinkedQueue<Long> script =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        ScriptedClock(long... readings) {
            long last = 0;
            for (long r : readings) {
                script.add(r);
                last = r;
            }
            frozen.set(last);
        }

        LongSupplier supplier() {
            return () -> {
                Long next = script.poll();
                if (next != null) {
                    frozen.set(next);
                    return next;
                }
                return frozen.get();
            };
        }

        long now() { return frozen.get(); }
        void set(long millis) { frozen.set(millis); }
        void advance(long delta) { frozen.addAndGet(delta); }
    }

    /** 读取底层内容存储的真实物理占用（所有块 size 之和），用于校验逻辑用量==物理占用。 */
    static long physicalUsedBytes(ObjectStoreService svc) {
        try {
            java.lang.reflect.Field f = ObjectStoreService.class.getDeclaredField("contentStore");
            f.setAccessible(true);
            return ((ContentStore) f.get(svc)).usedBytes();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
