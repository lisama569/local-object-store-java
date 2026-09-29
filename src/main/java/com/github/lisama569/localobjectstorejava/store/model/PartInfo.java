package com.github.lisama569.localobjectstorejava.store.model;

import java.io.Serializable;

/** 一个已提交分片的元数据。 */
public record PartInfo(
        int partNumber,
        /** 分片内容的 SHA-256（hex），同时作为内容寻址键 */
        String sha256,
        long size) implements Serializable {
    @SuppressWarnings("unused")
    private static final long serialVersionUID = 1L;
}
