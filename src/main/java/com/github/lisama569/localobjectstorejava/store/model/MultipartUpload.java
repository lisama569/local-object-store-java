package com.github.lisama569.localobjectstorejava.store.model;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/** 一次分片上传会话的元数据（可持久化，支持重启恢复与跨会话续传）。 */
public class MultipartUpload implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum State { ACTIVE, COMPLETED, ABORTED, EXPIRED }

    private final String uploadId;
    private final String owner;
    private final String key;
    private final long createdAtMillis;
    /** 滑动过期时间：任何会话交互都会顺延，连续空闲超过该时刻才过期 */
    private volatile long expiresAtMillis;
    private final Map<Integer, PartInfo> parts = new TreeMap<>();
    private volatile State state = State.ACTIVE;
    /** 完成幂等：完成后记录产生的版本号，重复 complete 返回同一结果 */
    private volatile String completedVersionId;
    /**
     * 可选的分片总数声明（创建会话时给出）。
     * 非 null 时，完成要求分片集合恰好为 1..expectedTotalParts；
     * null 表示未声明，沿用“按实际到齐分片完成”的老行为。
     */
    private final Integer expectedTotalParts;

    public MultipartUpload(String uploadId, String owner, String key,
                           long createdAtMillis, long expiresAtMillis) {
        this(uploadId, owner, key, createdAtMillis, expiresAtMillis, null);
    }

    public MultipartUpload(String uploadId, String owner, String key,
                           long createdAtMillis, long expiresAtMillis,
                           Integer expectedTotalParts) {
        this.uploadId = uploadId;
        this.owner = owner;
        this.key = key;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.expectedTotalParts = expectedTotalParts;
    }

    public String uploadId() { return uploadId; }
    public String owner() { return owner; }
    public String key() { return key; }
    public long createdAtMillis() { return createdAtMillis; }
    public long expiresAtMillis() { return expiresAtMillis; }
    public void touch(long newExpiresAtMillis) { this.expiresAtMillis = newExpiresAtMillis; }
    public State state() { return state; }
    public void state(State s) { this.state = s; }
    public Map<Integer, PartInfo> parts() { return parts; }
    public String completedVersionId() { return completedVersionId; }
    public void completedVersionId(String id) { this.completedVersionId = id; }
    /** 声明的分片总数；null 表示创建时未声明（老流程）。 */
    public Integer expectedTotalParts() { return expectedTotalParts; }
}
