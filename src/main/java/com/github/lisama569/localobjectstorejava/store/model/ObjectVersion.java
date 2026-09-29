package com.github.lisama569.localobjectstorejava.store.model;

import java.io.Serializable;
import java.util.List;

/** 对象的一个不可变版本。内容以分片内容哈希列表描述，整体哈希用于完整性校验。 */
public record ObjectVersion(
        String versionId,
        String owner,
        /** 各分片内容的 SHA-256，按分片序号排列 */
        List<String> partHashes,
        /** 整体内容的 SHA-256（hex） */
        String contentHash,
        long size,
        long createdAtMillis,
        /** 逻辑删除标记：删除操作生成删除标记版本而非物理移除 */
        boolean deleteMarker) implements Serializable {
    @SuppressWarnings("unused")
    private static final long serialVersionUID = 1L;
}
