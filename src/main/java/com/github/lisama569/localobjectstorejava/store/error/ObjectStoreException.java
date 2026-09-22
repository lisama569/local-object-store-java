package com.github.lisama569.localobjectstorejava.store.error;

/**
 * 对象存储统一异常：错误类型 + 面向调用方的可解释信息。
 * 底层异常只能作为 cause 附带，不允许直接透出给调用方。
 */
public class ObjectStoreException extends RuntimeException {

    private final ErrorType type;

    public ObjectStoreException(ErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public ObjectStoreException(ErrorType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public ErrorType getType() {
        return type;
    }
}
