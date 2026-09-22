package com.github.lisama569.localobjectstorejava.store.model;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/** 一次分片上传会话的元数据（可持久化，支持重启恢复与跨会话续传）。 */
public class MultipartUpload implements Serializable {

    public enum State { ACTIVE, COMPLETED, ABORTED }

    private final String uploadId;
    private final String owner;
    private final String key;
    private final long createdAtMillis;
    /** 滑动过期时间：任何会话交互都会顺延，已上传分片不会因超时丢失 */
    private volatile long expiresAtMillis;
    private final Map<Integer, PartInfo> parts = new TreeMap<>();
    private volatile State state = State.ACTIVE;
    /** 完成幂等：完成后记录产生的版本号，重复 complete 返回同一结果 */
    private volatile String completedVersionId;

    public MultipartUpload(String uploadId, String owner, String key,
                           long createdAtMillis, long expiresAtMillis) {
        this.uploadId = uploadId;
        this.owner = owner;
        this.key = key;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
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
}
