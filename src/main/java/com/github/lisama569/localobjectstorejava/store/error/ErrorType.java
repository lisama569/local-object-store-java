package com.github.lisama569.localobjectstorejava.store.error;

/**
 * 归一化后的错误类型，所有底层异常都必须映射为以下类型之一。
 */
public enum ErrorType {
    /** 对象或上传会话不存在（越权访问也统一映射为本类型，避免泄露存在性） */
    NOT_FOUND,
    /** 入参非法 */
    INVALID_ARGUMENT,
    /** 容量/分片数等配额超限 */
    QUOTA_EXCEEDED,
    /** 客户端提供的校验值与服务端计算结果不一致 */
    CHECKSUM_MISMATCH,
    /** 并发冲突（条件不满足、版本竞争失败） */
    CONFLICT,
    /** 未完成全部必要分片 */
    PARTS_INCOMPLETE,
    /** 调用方越权 */
    FORBIDDEN,
    /** 服务端本地存储故障（不直接透出底层异常细节） */
    STORAGE_FAULT
}
