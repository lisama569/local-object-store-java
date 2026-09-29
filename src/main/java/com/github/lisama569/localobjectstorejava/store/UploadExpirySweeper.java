package com.github.lisama569.localobjectstorejava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台扫描：回收连续空闲超过阈值的未完成上传会话。
 * 回收逻辑在元数据锁内完成，与续传/上报分片互斥。
 */
@Component
public class UploadExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(UploadExpirySweeper.class);

    private final StorageConfig config;
    private final ObjectStoreService service;

    public UploadExpirySweeper(StorageConfig config, ObjectStoreService service) {
        this.config = config;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${objectstore.expiry-sweep-interval-millis:60000}")
    public void sweep() {
        if (config.getExpirySweepIntervalMillis() <= 0) {
            return; // 配置关闭后台扫描，仅靠访问时惰性过期
        }
        int expired = service.sweepExpiredUploads();
        if (expired > 0) {
            log.info("sweeper reclaimed {} expired uploads", expired);
        }
    }
}
