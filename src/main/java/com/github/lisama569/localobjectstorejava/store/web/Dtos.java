package com.github.lisama569.localobjectstorejava.store.web;

import java.util.List;

/**
 * REST 层数据传输对象。
 */
public final class Dtos {

    private Dtos() {
    }

    public record InitiateRequest(String objectKey, Integer totalParts,
                                  String contentSha256, Long maxPartSize) {
    }

    public record InitiateResponse(String uploadId, String state, int totalParts) {
    }

    public record PartView(int partNumber, long size, String etag, long receivedAtMillis) {
    }

    public record UploadView(String uploadId, String objectKey, String state, int totalParts,
                             long uploadedBytes, List<PartView> parts) {
    }

    public record VersionView(String objectKey, String versionId, long size,
                              String contentSha256, long createdAtMillis) {
    }

    public record ErrorBody(String errorType, String message) {
    }
}
