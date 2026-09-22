package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.config.StoreProperties;
import com.github.lisama569.localobjectstorejava.store.digest.Checksums;
import com.github.lisama569.localobjectstorejava.store.metadata.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.model.Caller;
import com.github.lisama569.localobjectstorejava.store.quota.QuotaService;
import com.github.lisama569.localobjectstorejava.store.service.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.storage.BlobStore;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/**
 * 测试支撑：每个用例独享临时目录装配一套存储栈，
 * 并打印关键输入（分片数/大小/摘要）与判定依据（结果类型/引用计数/容量）。
 */
public abstract class StoreTestSupport {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @TempDir
    Path tempDir;

    protected StoreProperties properties;
    protected BlobStore blobStore;
    protected MetadataStore metadata;
    protected QuotaService quota;
    protected ObjectStoreService service;

    protected Caller tenant(String id) {
        return new Caller(id);
    }

    /** 默认装配（100KB 容量、10KB 分片上限）。 */
    protected void buildStore() {
        buildStore(100 * 1024L, 10 * 1024L, 1000);
    }

    protected void buildStore(long capacity, long maxPartSize, int maxParts) {
        properties = new StoreProperties();
        properties.setRootDir(tempDir.toString());
        properties.setCapacityBytes(capacity);
        properties.setDefaultMaxPartSize(maxPartSize);
        properties.setMaxPartsPerUpload(maxParts);
        blobStore = new BlobStore(tempDir);
        blobStore.init();
        metadata = new MetadataStore(tempDir);
        metadata.init();
        quota = new QuotaService(capacity);
        service = new ObjectStoreService(properties, blobStore, metadata, quota);
        service.reconcileAfterRestart();
        log.info("[setup] root={} capacity={} maxPartSize={}", tempDir, capacity, maxPartSize);
    }

    /** 模拟进程重启：同一磁盘目录重新装配全部组件并执行对账。 */
    protected ObjectStoreService restart() {
        blobStore = new BlobStore(tempDir);
        blobStore.init();
        metadata = new MetadataStore(tempDir);
        metadata.init();
        quota = new QuotaService(properties.getCapacityBytes());
        service = new ObjectStoreService(properties, blobStore, metadata, quota);
        service.reconcileAfterRestart();
        log.info("[restart] rebuilt stack on {}", tempDir);
        return service;
    }

    protected byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected byte[] repeated(byte value, int n) {
        byte[] data = new byte[n];
        java.util.Arrays.fill(data, value);
        return data;
    }

    protected String sha256(byte[] data) {
        return Checksums.sha256Hex(data);
    }

    protected byte[] download(Caller caller, String key, String versionId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.download(caller, key, versionId, out);
        return out.toByteArray();
    }

    protected ByteArrayInputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    protected byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }
}
