package com.github.lisama569.localobjectstorejava.web;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST 入口；调用方身份经 X-Owner 头传递。
 * 所有异常归一化为 {code, message}，不透出底层异常。
 */
@RestController
@RequestMapping("/api")
public class ObjectStoreController {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreController.class);

    private final ObjectStoreService service;

    public ObjectStoreController(ObjectStoreService service) {
        this.service = service;
    }

    public record CreateUploadRequest(String key) {}
    public record CreateUploadResponse(String uploadId, String key, long expiresAtMillis) {}
    public record PartResponse(int partNumber, String sha256, long size) {}
    public record VersionResponse(String versionId, String contentHash, long size,
                                  long createdAtMillis, boolean deleteMarker) {}
    public record ErrorResponse(String code, String message) {}

    @PostMapping("/uploads")
    public CreateUploadResponse createUpload(@RequestHeader("X-Owner") String owner,
                                             @RequestBody CreateUploadRequest req) {
        MultipartUpload u = service.createUpload(owner, req.key());
        return new CreateUploadResponse(u.uploadId(), u.key(), u.expiresAtMillis());
    }

    @GetMapping("/uploads/{uploadId}")
    public Map<String, Object> getUpload(@RequestHeader("X-Owner") String owner,
                                         @PathVariable String uploadId) {
        MultipartUpload u = service.getUpload(owner, uploadId);
        return Map.of(
                "uploadId", u.uploadId(),
                "key", u.key(),
                "state", u.state().name(),
                "expiresAtMillis", u.expiresAtMillis(),
                "parts", u.parts().values().stream()
                        .map(p -> new PartResponse(p.partNumber(), p.sha256(), p.size())).toList());
    }

    @PutMapping("/uploads/{uploadId}/parts/{partNumber}")
    public PartResponse uploadPart(@RequestHeader("X-Owner") String owner,
                                   @PathVariable String uploadId,
                                   @PathVariable int partNumber,
                                   @RequestHeader(value = "X-Expected-Sha256", required = false) String expected,
                                   @RequestBody byte[] data) {
        PartInfo p = service.uploadPart(owner, uploadId, partNumber, data, expected);
        return new PartResponse(p.partNumber(), p.sha256(), p.size());
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public VersionResponse complete(@RequestHeader("X-Owner") String owner,
                                    @PathVariable String uploadId,
                                    @RequestHeader(value = "X-Expected-Sha256", required = false) String expected) {
        return toResponse(service.completeUpload(owner, uploadId, expected));
    }

    @PostMapping("/uploads/{uploadId}/abort")
    public ResponseEntity<Void> abort(@RequestHeader("X-Owner") String owner,
                                      @PathVariable String uploadId) {
        service.abortUpload(owner, uploadId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/objects/{key}")
    public VersionResponse putObject(@RequestHeader("X-Owner") String owner,
                                     @PathVariable String key,
                                     @RequestHeader(value = "X-Expected-Sha256", required = false) String expected,
                                     @RequestBody byte[] data) {
        return toResponse(service.putObject(owner, key, data, expected));
    }

    @GetMapping("/objects/{key}")
    public byte[] getObject(@RequestHeader("X-Owner") String owner,
                            @PathVariable String key,
                            @RequestParam(required = false) String versionId) {
        return versionId == null
                ? service.getObject(owner, key)
                : service.getObjectVersion(owner, key, versionId);
    }

    @GetMapping("/objects/{key}/versions")
    public List<VersionResponse> listVersions(@RequestHeader("X-Owner") String owner,
                                              @PathVariable String key) {
        return service.listVersions(owner, key).stream().map(this::toResponse).toList();
    }

    @GetMapping("/objects")
    public List<String> listKeys(@RequestHeader("X-Owner") String owner) {
        return service.listKeys(owner);
    }

    @DeleteMapping("/objects/{key}")
    public ResponseEntity<Void> deleteObject(@RequestHeader("X-Owner") String owner,
                                             @PathVariable String key,
                                             @RequestParam(required = false) String versionId) {
        service.deleteObject(owner, key, versionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/usage")
    public Map<String, Long> usage(@RequestHeader("X-Owner") String owner) {
        return Map.of("usedBytes", service.usageOf(owner));
    }

    private VersionResponse toResponse(ObjectVersion v) {
        return new VersionResponse(v.versionId(), v.contentHash(), v.size(),
                v.createdAtMillis(), v.deleteMarker());
    }

    @ExceptionHandler(StoreException.class)
    public ResponseEntity<ErrorResponse> handleStore(StoreException e) {
        HttpStatus status = switch (e.code()) {
            case NOT_FOUND, ACCESS_DENIED -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case CHECKSUM_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case QUOTA_EXCEEDED, PART_SIZE_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_STATE -> HttpStatus.CONFLICT;
            case STORAGE_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(StoreException.Code.STORAGE_FAILURE.name(), "内部错误"));
    }
}
