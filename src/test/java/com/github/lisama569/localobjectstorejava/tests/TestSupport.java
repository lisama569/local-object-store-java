package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StorageConfig;

import java.nio.file.Path;

/** 测试基座：在临时目录上构建独立的存储栈。 */
final class TestSupport {

    private TestSupport() {}

    static ObjectStoreService newService(Path dir, long quotaBytes, long maxPartSize) {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dir.toString());
        config.setQuotaBytes(quotaBytes);
        config.setMaxPartSize(maxPartSize);
        return new ObjectStoreService(config, new ContentStore(config), new MetadataStore(config));
    }

    static ObjectStoreService newService(Path dir) {
        return newService(dir, 64L * 1024 * 1024, 8L * 1024 * 1024);
    }

    /** 自定义滑动空闲过期阈值（毫秒），其余沿用默认。 */
    static ObjectStoreService newServiceWithTtl(Path dir, long ttlMillis) {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dir.toString());
        config.setExpirySweepIntervalMillis(0L); // 单测里手动控制过期，禁用后台扫描
        config.setUploadTtlMillis(ttlMillis);
        return new ObjectStoreService(config, new ContentStore(config), new MetadataStore(config));
    }

    /** 自定义滑动空闲过期阈值，并返回持有底层组件的测试夹具。 */
    static Fixture fixtureWithTtl(Path dir, long ttlMillis) {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dir.toString());
        config.setExpirySweepIntervalMillis(0L);
        config.setUploadTtlMillis(ttlMillis);
        ContentStore contentStore = new ContentStore(config);
        MetadataStore metadataStore = new MetadataStore(config);
        return new Fixture(new ObjectStoreService(config, contentStore, metadataStore),
                contentStore, metadataStore, config);
    }

    record Fixture(ObjectStoreService service, ContentStore contentStore,
                   MetadataStore metadataStore, StorageConfig config) {
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
