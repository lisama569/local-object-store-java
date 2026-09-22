package com.github.lisama569.localobjectstorejava.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储可配置参数：
 * <ul>
 *   <li>rootDir：数据根目录（blobs/ 与 meta/）；</li>
 *   <li>capacityBytes：全仓逻辑容量上限（按唯一 blob 占用计费）；</li>
 *   <li>defaultMaxPartSize：默认单个分片字节上限；</li>
 *   <li>maxPartsPerUpload：单次上传分片数上限。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "objectstore")
public class StoreProperties {

    private String rootDir = "data/objectstore";
    private long capacityBytes = 100L * 1024 * 1024;
    private long defaultMaxPartSize = 8L * 1024 * 1024;
    private int maxPartsPerUpload = 1000;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public void setCapacityBytes(long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public long getDefaultMaxPartSize() {
        return defaultMaxPartSize;
    }

    public void setDefaultMaxPartSize(long defaultMaxPartSize) {
        this.defaultMaxPartSize = defaultMaxPartSize;
    }

    public int getMaxPartsPerUpload() {
        return maxPartsPerUpload;
    }

    public void setMaxPartsPerUpload(int maxPartsPerUpload) {
        this.maxPartsPerUpload = maxPartsPerUpload;
    }
}
