package cn.teamone.shared.api;

import java.util.List;

/**
 * 业务规则异常（状态机非法流转 / 门禁阻塞 / 乐观锁冲突等 4xx 语义）。
 *
 * <p>与 {@link PermissionDeniedException} 同构：HTTP 层按 {@link #errorCode()} 的 httpStatus
 * 映射（PRD_4201/PRD_4230→422、PLT_4091→409），错误码必须来自 {@link ErrorCode} 目录。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    public BusinessException(ErrorCode errorCode, String message, List<String> details) {
        super(message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : details;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 错误码全文（如 T1-PRD-4201） */
    public String code() {
        return errorCode.code();
    }

    public List<String> details() {
        return details;
    }
}
