package com.github.lisama569.localobjectstorejava.store.model;

/**
 * 调用方身份。访问范围以 tenant（租户/桶）隔离。
 */
public record Caller(String tenantId) {
    public Caller {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
