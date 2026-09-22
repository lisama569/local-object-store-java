package com.github.lisama569.localobjectstorejava.store.model;

/**
 * 单个已接收分片的回执：分片号、大小、内容 sha256（etag）与接收时间。
 */
public record PartReceipt(int partNumber, long size, String etag, long receivedAtMillis) {
}
