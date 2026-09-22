package com.github.lisama569.localobjectstorejava.store.web;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 归一化错误到 HTTP 响应。底层异常只进日志，响应体只含错误类型与可解释信息。
 */
@RestControllerAdvice
public class StoreExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StoreExceptionHandler.class);

    @ExceptionHandler(ObjectStoreException.class)
    public ResponseEntity<Dtos.ErrorBody> handle(ObjectStoreException ex) {
        HttpStatus status = statusOf(ex.getType());
        // 4xx 记 info（客户端原因），5xx 记 warn（服务端原因，含 cause 堆栈）
        if (status.is5xxServerError()) {
            log.warn("[api-error] type={} message={}", ex.getType(), ex.getMessage(), ex);
        } else {
            log.info("[api-error] type={} message={}", ex.getType(), ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(new Dtos.ErrorBody(ex.getType().name(), ex.getMessage()));
    }

    /** 缺少租户头等参数问题按 INVALID_ARGUMENT 归一化，不透出 Spring 原生异常。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Dtos.ErrorBody> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorBody(ErrorType.INVALID_ARGUMENT.name(),
                        "missing required header: " + ex.getHeaderName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorBody> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorBody(ErrorType.INVALID_ARGUMENT.name(), ex.getMessage()));
    }

    /** 兜底：任何未预期异常都映射为 STORAGE_FAULT，绝不把堆栈/底层异常透出。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorBody> handleUnexpected(Exception ex) {
        log.error("[api-error] unexpected failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorBody(ErrorType.STORAGE_FAULT.name(),
                        "internal storage failure"));
    }

    public static HttpStatus statusOf(ErrorType type) {
        return switch (type) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case CHECKSUM_MISMATCH, QUOTA_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PARTS_INCOMPLETE, CONFLICT -> HttpStatus.CONFLICT;
            case STORAGE_FAULT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
