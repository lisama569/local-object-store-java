package com.github.lisama569.localobjectstorejava.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 可配置的存储约束：容量、分片大小、上传会话有效期。 */
@Component
@ConfigurationProperties(prefix = "objectstore")
public class StorageConfig {

    /** 数据与元数据落盘根目录 */
    private String rootDir = "data/object-store";

    /** 每个调用方（owner）的字节配额 */
    private long quotaBytes = 64L * 1024 * 1024;

    /** 单个分片允许的最大字节数 */
    private long maxPartSize = 8L * 1024 * 1024;

    /** 最大分片数量 */
    private int maxParts = 10_000;

    /** 未完成上传会话的滑动空闲过期阈值（毫秒）：连续空闲超过该值即过期 */
    private long uploadTtlMillis = 24L * 3600 * 1000;

    /** 后台过期扫描间隔（毫秒）；<=0 表示关闭后台扫描，改为访问时惰性过期 */
    private long expirySweepIntervalMillis = 60_000L;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public long getQuotaBytes() { return quotaBytes; }
    public void setQuotaBytes(long quotaBytes) { this.quotaBytes = quotaBytes; }
    public long getMaxPartSize() { return maxPartSize; }
    public void setMaxPartSize(long maxPartSize) { this.maxPartSize = maxPartSize; }
    public int getMaxParts() { return maxParts; }
    public void setMaxParts(int maxParts) { this.maxParts = maxParts; }
    public long getUploadTtlMillis() { return uploadTtlMillis; }
    public void setUploadTtlMillis(long uploadTtlMillis) { this.uploadTtlMillis = uploadTtlMillis; }
    public long getExpirySweepIntervalMillis() { return expirySweepIntervalMillis; }
    public void setExpirySweepIntervalMillis(long v) { this.expirySweepIntervalMillis = v; }
}
