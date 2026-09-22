package com.github.lisama569.localobjectstorejava.store.model;

/**
 * 物理数据块元数据。blobKey 即内容的 SHA-256（hex），天然支持去重。
 * refCount 为引用它的对象版本数（启动时由版本表重建，保证计数不会漂移）。
 */
public record BlobRecord(String key, long size, int refCount, long createdAtMillis) {

    public BlobRecord withRefCount(int newRefCount) {
        return new BlobRecord(key, size, newRefCount, createdAtMillis);
    }
}
