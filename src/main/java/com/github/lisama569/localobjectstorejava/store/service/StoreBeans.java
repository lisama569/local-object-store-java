package com.github.lisama569.localobjectstorejava.store.service;

import com.github.lisama569.localobjectstorejava.store.config.StoreProperties;
import com.github.lisama569.localobjectstorejava.store.metadata.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.quota.QuotaService;
import com.github.lisama569.localobjectstorejava.store.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储组件装配。初始化顺序固定：
 * BlobStore(磁盘目录) -> MetadataStore(加载 JSON 重建索引) -> QuotaService
 * -> ObjectStoreService.reconcileAfterRestart()(分片恢复/引用计数/容量基线)。
 */
@Configuration
public class StoreBeans {

    private static final Logger log = LoggerFactory.getLogger(StoreBeans.class);

    @Bean
    public BlobStore blobStore(StoreProperties properties) {
        Path root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
        log.info("[bootstrap] storage root: {}", root);
        BlobStore store = new BlobStore(root);
        store.init();
        return store;
    }

    @Bean
    public MetadataStore metadataStore(StoreProperties properties, BlobStore blobStore) {
        MetadataStore store = new MetadataStore(blobStore.root());
        store.init();
        return store;
    }

    @Bean
    public QuotaService quotaService(StoreProperties properties) {
        return new QuotaService(properties.getCapacityBytes());
    }

    @Bean
    public ObjectStoreService objectStoreService(StoreProperties properties,
                                                 BlobStore blobStore,
                                                 MetadataStore metadataStore,
                                                 QuotaService quotaService) {
        ObjectStoreService service = new ObjectStoreService(
                properties, blobStore, metadataStore, quotaService);
        service.reconcileAfterRestart();
        return service;
    }
}
