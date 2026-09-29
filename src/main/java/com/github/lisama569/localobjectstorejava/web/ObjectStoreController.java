package com.github.lisama569.localobjectstorejava.web;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
import org.springframework.http.converter.HttpMessageNotReadableException;

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

    public record CreateUploadRequest(String key, Integer expectedParts) {}
    public record CreateUploadResponse(String uploadId, String key, long expiresAtMillis,
                                       Integer expectedParts) {}
    public record PartResponse(int partNumber, String sha256, long size) {}
    public record VersionResponse(String versionId, String contentHash, long size,
                                  long createdAtMillis, boolean deleteMarker) {}
    public record ErrorResponse(String code, String message, Object details) {
        ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }

    @PostMapping("/uploads")
    public CreateUploadResponse createUpload(@RequestHeader("X-Owner") String owner,
                                             @RequestBody CreateUploadRequest req) {
        MultipartUpload u = service.createUpload(owner, req.key(), req.expectedParts());
        return new CreateUploadResponse(u.uploadId(), u.key(), u.expiresAtMillis(), u.expectedParts());
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
                "lastInteractAtMillis", u.lastInteractAtMillis(),
                "expectedParts", u.expectedParts() == null ? -1 : u.expectedParts(),
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

    /**
     * 区间读取：优先使用标准 Range: bytes=start-end 头（end 可省略表示到末尾）；
     * 也支持 start/end 查询参数（end 可省略）。命中时返回 206 与 Content-Range。
     */
    @GetMapping(value = "/objects/{key}", params = {"start"})
    public ResponseEntity<byte[]> getObjectRange(@RequestHeader("X-Owner") String owner,
                                                 @PathVariable String key,
                                                 @RequestParam(required = false) String versionId,
                                                 @RequestParam long start,
                                                 @RequestParam(required = false, defaultValue = "-1") long end,
                                                 @RequestHeader(value = "Range", required = false) String rangeHeader) {
        long[] range = resolveRange(rangeHeader, start, end);
        ObjectStoreService.RangeRead r = versionId == null
                ? service.getObjectRange(owner, key, range[0], range[1])
                : service.getObjectVersionRange(owner, key, versionId, range[0], range[1]);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + r.start() + "-" + r.end() + "/" + r.totalSize())
                .contentLength(r.data().length)
                .body(r.data());
    }

    /** 仅带 Range 头（无 start 参数）时也走区间读取。 */
    @GetMapping(value = "/objects/{key}", headers = "Range")
    public ResponseEntity<byte[]> getObjectByRangeHeader(@RequestHeader("X-Owner") String owner,
                                                         @PathVariable String key,
                                                         @RequestParam(required = false) String versionId,
                                                         @RequestHeader("Range") String rangeHeader) {
        long[] range = resolveRange(rangeHeader, -1, -1);
        ObjectStoreService.RangeRead r = versionId == null
                ? service.getObjectRange(owner, key, range[0], range[1])
                : service.getObjectVersionRange(owner, key, versionId, range[0], range[1]);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + r.start() + "-" + r.end() + "/" + r.totalSize())
                .contentLength(r.data().length)
                .body(r.data());
    }

    /** 解析 bytes=start-end；start=-1 表示只接受 Range 头。语法非法返回 BAD_REQUEST。 */
    private long[] resolveRange(String rangeHeader, long paramStart, long paramEnd) {
        if (rangeHeader != null) {
            String prefix = "bytes=";
            String spec = rangeHeader.trim();
            if (!spec.startsWith(prefix) || spec.contains(",")) {
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "不支持的 Range 头（仅支持单个 bytes=start-end 区间）: " + rangeHeader);
            }
            String part = spec.substring(prefix.length()).trim();
            int dash = part.indexOf('-');
            if (dash < 0) {
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "Range 头语法非法: " + rangeHeader);
            }
            String s = part.substring(0, dash).trim();
            String e = part.substring(dash + 1).trim();
            long start;
            long end;
            try {
                start = s.isEmpty() ? -1 : Long.parseLong(s);
                end = e.isEmpty() ? -1 : Long.parseLong(e);
            } catch (NumberFormatException nfe) {
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "Range 头不是数字: " + rangeHeader);
            }
            if (s.isEmpty()) {
                // suffix 形式 bytes=-N：最后 N 个字节；具体起点要先知道长度，这里交给校验
                throw new StoreException(StoreException.Code.BAD_REQUEST,
                        "暂不支持 suffix 区间 bytes=-N，请显式给出 start: " + rangeHeader);
            }
            return new long[]{start, end};
        }
        if (paramStart < 0) {
            throw new StoreException(StoreException.Code.BAD_REQUEST, "必须提供 start 或 Range 头");
        }
        return new long[]{paramStart, paramEnd};
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

    /** 调用方当前未完成上传占用的分片字节（过期会话已回收，不计入）。 */
    @GetMapping("/usage/unfinished-uploads")
    public Map<String, Long> unfinishedUploadUsage(@RequestHeader("X-Owner") String owner) {
        return Map.of("unfinishedUploadBytes", service.unfinishedUploadBytes(owner));
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
            case CONFLICT, MISSING_PARTS, INVALID_STATE -> HttpStatus.CONFLICT;
            case UPLOAD_EXPIRED -> HttpStatus.GONE;
            case RANGE_NOT_SATISFIABLE -> HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
            case STORAGE_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.code().name(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        // 典型场景：PUT 分片/对象时漏发请求体；归为 BAD_REQUEST 而非 500
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(StoreException.Code.BAD_REQUEST.name(),
                        "请求体缺失或无法解析（二进制写入请带 Content-Type: application/octet-stream）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(StoreException.Code.STORAGE_FAILURE.name(), "内部错误"));
    }
}
