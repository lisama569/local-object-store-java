package com.github.lisama569.localobjectstorejava.store.web;

import com.github.lisama569.localobjectstorejava.store.model.Caller;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartReceipt;
import com.github.lisama569.localobjectstorejava.store.service.ObjectStoreService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 对象存储 REST 接口。租户通过 X-Tenant-Id 头标识；
 * 元数据查询与内容下载都先经过 service 的同一份归属判定。
 */
@RestController
@RequestMapping("/api/objects")
public class ObjectStoreController {

    private final ObjectStoreService service;

    public ObjectStoreController(ObjectStoreService service) {
        this.service = service;
    }

    private Caller caller(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("X-Tenant-Id header is required");
        }
        return new Caller(tenant.trim());
    }

    private Dtos.VersionView toView(ObjectVersion v) {
        return new Dtos.VersionView(v.objectKey(), v.versionId(), v.size(),
                v.contentSha256(), v.createdAtMillis());
    }

    private Dtos.UploadView toUploadView(MultipartUpload u) {
        List<Dtos.PartView> parts = u.parts().values().stream()
                .map(p -> new Dtos.PartView(p.partNumber(), p.size(), p.etag(),
                        p.receivedAtMillis()))
                .toList();
        return new Dtos.UploadView(u.uploadId(), u.objectKey(), u.state(),
                u.totalParts(), u.uploadedBytes(), parts);
    }

    // ---------------- 分片上传 ----------------

    @PostMapping("/multipart")
    public ResponseEntity<Dtos.InitiateResponse> initiate(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestBody Dtos.InitiateRequest request) {
        if (request == null || request.objectKey() == null) {
            throw new IllegalArgumentException("objectKey is required in body");
        }
        int totalParts = request.totalParts() == null ? 1 : request.totalParts();
        MultipartUpload upload = service.initiate(caller(tenant), request.objectKey(),
                totalParts, request.contentSha256(), request.maxPartSize());
        return ResponseEntity.ok(new Dtos.InitiateResponse(
                upload.uploadId(), upload.state(), upload.totalParts()));
    }

    @GetMapping("/multipart/{uploadId}")
    public ResponseEntity<Dtos.UploadView> status(
            @RequestHeader("X-Tenant-Id") String tenant,
            @PathVariable String uploadId) {
        return ResponseEntity.ok(toUploadView(service.getUpload(caller(tenant), uploadId)));
    }

    @PutMapping(value = "/multipart/{uploadId}/parts/{partNumber}",
            consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Dtos.PartView> putPart(
            @RequestHeader("X-Tenant-Id") String tenant,
            @PathVariable String uploadId,
            @PathVariable int partNumber,
            @RequestHeader(value = "X-Part-Sha256", required = false) String partSha256,
            InputStream rawBody) throws java.io.IOException {
        // 直接读取原始字节流：避免 x-www-form-urlencoded 被表单过滤器预消费导致空 body
        byte[] data = rawBody.readAllBytes();
        PartReceipt receipt = service.uploadPart(caller(tenant), uploadId, partNumber,
                new ByteArrayInputStream(data), partSha256);
        return ResponseEntity.ok(new Dtos.PartView(receipt.partNumber(), receipt.size(),
                receipt.etag(), receipt.receivedAtMillis()));
    }

    @PostMapping("/multipart/{uploadId}/complete")
    public ResponseEntity<Dtos.VersionView> complete(
            @RequestHeader("X-Tenant-Id") String tenant,
            @PathVariable String uploadId,
            @RequestHeader(value = "X-Content-Sha256", required = false) String contentSha256) {
        return ResponseEntity.ok(toView(
                service.completeUpload(caller(tenant), uploadId, contentSha256)));
    }

    @DeleteMapping("/multipart/{uploadId}")
    public ResponseEntity<Void> abort(
            @RequestHeader("X-Tenant-Id") String tenant,
            @PathVariable String uploadId) {
        service.abortUpload(caller(tenant), uploadId);
        return ResponseEntity.noContent().build();
    }

    // ---------------- 一次性写入 / 读取 / 删除 ----------------

    @PutMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Dtos.VersionView> put(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam String objectKey,
            @RequestHeader(value = "X-Content-Sha256", required = false) String contentSha256,
            InputStream rawBody) throws java.io.IOException {
        byte[] data = rawBody.readAllBytes();
        ObjectVersion version = service.put(caller(tenant), objectKey,
                new ByteArrayInputStream(data), contentSha256);
        return ResponseEntity.ok(toView(version));
    }

    @GetMapping
    public ResponseEntity<byte[]> get(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam String objectKey,
            @RequestParam(required = false) String versionId) {
        ObjectVersion version = service.get(caller(tenant), objectKey, versionId);
        byte[] data;
        try (var in = service.openContent(caller(tenant), objectKey, versionId)) {
            data = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException(
                    com.github.lisama569.localobjectstorejava.store.error.ErrorType.STORAGE_FAULT,
                    "failed to read content", e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-Version-Id", version.versionId());
        headers.set("X-Content-Sha256", version.contentSha256());
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @GetMapping("/versions")
    public ResponseEntity<List<Dtos.VersionView>> versions(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam String objectKey) {
        return ResponseEntity.ok(service.listVersions(caller(tenant), objectKey)
                .stream().map(this::toView).toList());
    }

    @GetMapping("/list")
    public ResponseEntity<List<String>> list(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam(defaultValue = "") String prefix) {
        return ResponseEntity.ok(service.listObjects(caller(tenant), prefix));
    }

    @GetMapping("/quota")
    public ResponseEntity<java.util.Map<String, Long>> quota(
            @RequestHeader("X-Tenant-Id") String tenant) {
        caller(tenant);
        return ResponseEntity.ok(java.util.Map.of(
                "capacityBytes", service.capacityTotal(),
                "usedBytes", service.capacityUsed(),
                "availableBytes", service.capacityTotal() - service.capacityUsed()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam String objectKey,
            @RequestParam(required = false) String versionId) {
        if (versionId == null || versionId.isBlank()) {
            service.deleteAllVersions(caller(tenant), objectKey);
        } else {
            service.deleteVersion(caller(tenant), objectKey, versionId);
        }
        return ResponseEntity.noContent().build();
    }
}
