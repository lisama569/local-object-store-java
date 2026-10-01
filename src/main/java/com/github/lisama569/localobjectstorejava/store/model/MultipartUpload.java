package com.github.lisama569.localobjectstorejava.store.model;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/** 一次分片上传会话的元数据（可持久化，支持重启恢复与跨会话续传）。 */
public class MultipartUpload implements Serializable {

    public enum State { ACTIVE, COMPLETED, ABORTED, EXPIRED }

    private final String uploadId;
    private final String owner;
    private final String key;
    private final long createdAtMillis;
    /** 滑动过期时间：任何会话交互都会顺延，已上传分片不会因超时丢失 */
    private volatile long expiresAtMillis;
    /** 最近一次会话交互（创建/查询/提交分片）时间，连续空闲超过阈值才判定过期 */
    private volatile long lastInteractAtMillis;
    /** 创建时可选声明的分片总数；null 表示未声明，保持老流程语义 */
    private final Integer expectedParts;
    private final Map<Integer, PartInfo> parts = new TreeMap<>();
    private volatile State state = State.ACTIVE;
    /** 完成幂等：完成后记录产生的版本号，重复 complete 返回同一结果 */
    private volatile String completedVersionId;

    /** 未声明分片总数的构造（兼容老流程与旧快照反序列化）。 */
    public MultipartUpload(String uploadId, String owner, String key,
                           long createdAtMillis, long expiresAtMillis) {
        this(uploadId, owner, key, createdAtMillis, expiresAtMillis, null);
    }

    public MultipartUpload(String uploadId, String owner, String key,
                           long createdAtMillis, long expiresAtMillis, Integer expectedParts) {
        this.uploadId = uploadId;
        this.owner = owner;
        this.key = key;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.lastInteractAtMillis = createdAtMillis;
        this.expectedParts = expectedParts;
    }

    public String uploadId() { return uploadId; }
    public String owner() { return owner; }
    public String key() { return key; }
    public long createdAtMillis() { return createdAtMillis; }
    public long expiresAtMillis() { return expiresAtMillis; }
    /** 显式记录交互时间与新的过期时间（由服务层按当前时钟计算）。 */
    public void interactedAt(long nowMillis, long newExpiresAtMillis) {
        this.lastInteractAtMillis = nowMillis;
        this.expiresAtMillis = newExpiresAtMillis;
    }
    public long lastInteractAtMillis() { return lastInteractAtMillis; }
    public Integer expectedParts() { return expectedParts; }
    public State state() { return state; }
    public void state(State s) { this.state = s; }
    public Map<Integer, PartInfo> parts() { return parts; }
    public String completedVersionId() { return completedVersionId; }
    public void completedVersionId(String id) { this.completedVersionId = id; }

    /**
     * 兼容旧快照：lastInteractAtMillis 是空闲过期特性引入的字段，旧版本序列化的会话里不存在，
     * 反序列化后为默认值 0。若不修正，启用空闲过期后这些历史会话会因 now-0 超过阈值被立刻误回收。
     * 统一回填为创建时间（等价于“创建后尚未发生额外交互”），保持既有会话原样、不要求调用方改造。
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (lastInteractAtMillis == 0L) {
            lastInteractAtMillis = createdAtMillis;
        }
    }
}
