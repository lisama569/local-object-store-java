package com.github.lisama569.localobjectstorejava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 上传会话空闲过期的后台回收器。
 *
 * <p>惰性清扫只能覆盖“后续还有请求打进来”的会话；调用方彻底放弃上传、再也不发任何
 * 请求时，分片容量会一直挂在底层。本回收器按固定间隔在元数据锁内执行一次全量清扫，
 * 让空闲到期的会话即使没有任何后续请求也能被真正回收。
 *
 * <p>回收动作最终走 {@link ObjectStoreService#sweepAllExpired()}，与请求路径使用同一把
 * 元数据锁、同一套 {@code expireIfIdle} 判定：一次过期只回收一次，不会与续传/上报
 * 产生中间态，也不会重复扣减。正在持续交互的会话 {@code lastInteractAtMillis} 不断
 * 被滑动续期，后台任务不会误回收。
 *
 * <p>{@code objectstore.upload-idle-ttl-millis<=0}（关闭过期）时不启动调度。
 * 定时器跑在守护线程上，JVM 退出不会被阻塞；测试可直接调用 {@link #runSweepOnce()}
 * 配合可控时钟做确定性验证，而不必等待真实墙钟。
 */
@Component
public class UploadExpiryReaper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UploadExpiryReaper.class);

    private final ObjectStoreService service;
    private final long intervalMillis;
    private final boolean enabled;
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    public UploadExpiryReaper(ObjectStoreService service, StorageConfig config) {
        this.service = service;
        this.enabled = config.getUploadIdleTtlMillis() > 0;
        this.intervalMillis = config.getUploadSweepIntervalMillis() > 0
                ? config.getUploadSweepIntervalMillis()
                : StorageConfig.DEFAULT_SWEEP_INTERVAL_MILLIS;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!enabled) {
            log.info("upload idle expiry disabled, background reaper not scheduled");
            running = true;
            return;
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "upload-expiry-reaper");
            t.setDaemon(true);
            return t;
        });
        // 启动后先等一个间隔再扫，避免与服务启动期的请求抢锁；之后固定频率执行。
        exec.scheduleWithFixedDelay(this::runSweepOnce, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        this.executor = exec;
        this.running = true;
        log.info("upload expiry reaper started, intervalMs={}", intervalMillis);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        ScheduledExecutorService exec = this.executor;
        if (exec != null) {
            exec.shutdownNow();
            this.executor = null;
        }
        this.running = false;
        log.info("upload expiry reaper stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 执行一轮全量过期回收。public 以便在可控时钟的测试中直接驱动，避免依赖真实定时。
     * 任何异常都只记录不外抛：后台线程不能因单次存储抖动而静默死亡。
     */
    public void runSweepOnce() {
        try {
            service.sweepAllExpired();
        } catch (RuntimeException e) {
            log.warn("scheduled expiry sweep failed, will retry next interval", e);
        }
    }
}
