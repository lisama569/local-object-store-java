package com.github.lisama569.localobjectstorejava.store.digest;

import java.io.IOException;

/**
 * 流式拷贝时发现实际字节数超过调用方给定上限。
 * 与容量超限（逻辑配额）区分：这是单分片/单对象大小约束被物理突破。
 */
public class SizeLimitExceededException extends IOException {

    private final long limit;
    private final long actual;

    public SizeLimitExceededException(long limit, long actual) {
        super("size limit exceeded: limit=" + limit + " actual>=" + actual);
        this.limit = limit;
        this.actual = actual;
    }

    public long getLimit() {
        return limit;
    }

    public long getActual() {
        return actual;
    }
}
