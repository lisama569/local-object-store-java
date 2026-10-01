package com.github.lisama569.localobjectstorejava.store;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 未完成上传会话的后台过期清扫器。
 *
 * <p>仅靠惰性清扫时，调用方彻底放弃上传后不再发任何请求，其分片占用将永远挂着；
 * 本组件按固定周期在进程内主动调用 {@link ObjectStoreService#sweepAllExpired()}，
 * 与所有前台请求共用同一把元数据锁，因此回收与续传/上报严格互斥、一次过期只回收一次。
 *
 * <p>{@code objectstore.upload-idle-sweep-millis <= 0} 时不启用（单测手工构造服务栈时
 * 也不会创建本组件）。
 */
@Component
public class UploadExpiryReaper {

    private static final Logger log = LoggerFactory.getLogger(UploadExpiryReaper.class);

    private final StorageConfig config;
    private final ObjectStoreService service;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public UploadExpiryReaper(StorageConfig config, ObjectStoreService service) {
        this.config = config;
        this.service = service;
    }

    @PostConstruct
    void start() {
        long intervalMillis = config.getUploadIdleSweepMillis();
        if (intervalMillis <= 0) {
            log.info("upload expiry background sweep disabled (upload-idle-sweep-millis<=0)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "upload-expiry-reaper");
            t.setDaemon(true);
            return t;
        });
        // 首次延迟一个周期执行，避免启动瞬间与恢复逻辑争抢
        task = executor.scheduleWithFixedDelay(this::sweepSafely,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("upload expiry background sweep enabled intervalMillis={}", intervalMillis);
    }

    private void sweepSafely() {
        try {
            service.sweepAllExpired();
        } catch (Throwable t) {
            // 清扫失败不能杀死调度线程；下一周期会重试，过期回收仍是幂等的
            log.warn("background upload expiry sweep failed, will retry: {}", t.toString(), t);
        }
    }

    @PreDestroy
    void stop() {
        if (task != null) {
            task.cancel(false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
