package com.github.lisama569.localobjectstorejava.store;

/**
 * 归一化后的存储异常。所有底层错误、元数据冲突与客户端错误
 * 都映射为带 {@link Code} 的本异常，不向调用方透出原始异常类型。
 */
public class StoreException extends RuntimeException {

    public enum Code {
        /** 对象/版本/上传不存在（同时对越权访问返回，避免泄露存在性） */
        NOT_FOUND,
        /** 调用方无权限（对外表现为 NOT_FOUND，内部保留此码用于日志） */
        ACCESS_DENIED,
        /** 分片或整体校验和不一致 */
        CHECKSUM_MISMATCH,
        /** 容量配额超限 */
        QUOTA_EXCEEDED,
        /** 分片大小超限 */
        PART_SIZE_EXCEEDED,
        /** 元数据冲突（并发修改、重复提交冲突等） */
        CONFLICT,
        /** 状态非法（如上传已完成/已中止/已过期） */
        INVALID_STATE,
        /** 声明了分片总数，但完成时仍有缺口（与校验失败、状态非法相区分） */
        MISSING_PARTS,
        /** 上传会话因连续空闲超过阈值已过期，容量已被回收 */
        UPLOAD_EXPIRED,
        /** 区间读取的范围越界（start/end 超出对象长度） */
        RANGE_NOT_SATISFIABLE,
        /** 底层存储失败 */
        STORAGE_FAILURE,
        /** 请求参数非法 */
        BAD_REQUEST
    }

    private final Code code;
    /** 结构化补充信息（如 MISSING_PARTS 时的缺失分片号列表），可为空 */
    private final transient Object details;

    public StoreException(Code code, String message) {
        this(code, message, null);
    }

    public StoreException(Code code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public StoreException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = null;
    }

    public Code code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
