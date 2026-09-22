package com.github.lisama569.localobjectstorejava.store.quota;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 容量配额（全仓统一的逻辑容量）。
 * <ul>
 *   <li>占用 = 唯一 blob 字节数 + 在途（已接收分片、拼接临时文件）字节数；</li>
 *   <li>reserve/release 成对出现，任何失败路径都必须回滚，保证容量不泄漏；</li>
 *   <li>超限抛 QUOTA_EXCEEDED，信息中给出 capacity/used/requested，原因可解释。</li>
 * </ul>
 */
public class QuotaService {

    private final long capacityBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    public QuotaService(long capacityBytes) {
        if (capacityBytes < 0) {
            throw new IllegalArgumentException("capacityBytes must be non-negative");
        }
        this.capacityBytes = capacityBytes;
    }

    public long used() {
        return usedBytes.get();
    }

    public long capacity() {
        return capacityBytes;
    }

    public long available() {
        return capacityBytes - usedBytes.get();
    }

    /** 启动对账后以磁盘事实重建占用基线。 */
    public void resetUsed(long used) {
        if (used < 0 || used > capacityBytes) {
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                    "invalid quota baseline after restart: used=" + used
                            + " capacity=" + capacityBytes);
        }
        usedBytes.set(used);
    }

    public void reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reserved bytes must be non-negative");
        }
        long updated;
        while (true) {
            long current = usedBytes.get();
            updated = current + bytes;
            if (updated > capacityBytes) {
                throw new ObjectStoreException(ErrorType.QUOTA_EXCEEDED,
                        "capacity exceeded: capacity=" + capacityBytes
                                + " used=" + current + " requested=" + bytes);
            }
            if (usedBytes.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    public void release(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("released bytes must be non-negative");
        }
        long updated;
        while (true) {
            long current = usedBytes.get();
            updated = current - bytes;
            if (updated < 0) {
                throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                        "quota accounting underflow: tried to release=" + bytes
                                + " but used=" + current);
            }
            if (usedBytes.compareAndSet(current, updated)) {
                return;
            }
        }
    }
}
