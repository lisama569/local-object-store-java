package com.github.lisama569.localobjectstorejava.store.model;

/**
 * 对象的一个已提交版本。版本一经创建即为不可变快照；覆盖写产生新版本而非原地修改，
 * 因此读取方只会看到完整版本，不会读到半写内容。
 */
public record ObjectVersion(
        String tenant,
        String objectKey,
        String versionId,
        String blobKey,
        long size,
        String contentSha256,
        long createdAtMillis,
        String uploadId
) {
}
