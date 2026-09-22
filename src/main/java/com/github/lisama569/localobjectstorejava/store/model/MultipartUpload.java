package com.github.lisama569.localobjectstorejava.store.model;

import java.util.Collections;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.TreeMap;

/**
 * 分片上传会话。状态只能为 ACTIVE / COMPLETED / ABORTED。
 * parts 记录已成功落盘并通过校验的分片；重复提交同号分片时以内容 etag 判定幂等或冲突。
 */
public record MultipartUpload(
        String uploadId,
        String tenant,
        String objectKey,
        long initiatedAtMillis,
        int totalParts,
        long maxPartSize,
        String expectedObjectSha256,
        String state,
        Map<Integer, PartReceipt> parts,
        long uploadedBytes
) {
    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";

    public MultipartUpload {
        parts = Collections.unmodifiableMap(new TreeMap<>(parts));
    }

    @JsonIgnore
    public boolean isActive() {
        return ACTIVE.equals(state);
    }

    @JsonIgnore
    public boolean allPartsPresent() {
        if (parts.size() != totalParts) {
            return false;
        }
        for (int i = 1; i <= totalParts; i++) {
            if (!parts.containsKey(i)) {
                return false;
            }
        }
        return true;
    }
}
