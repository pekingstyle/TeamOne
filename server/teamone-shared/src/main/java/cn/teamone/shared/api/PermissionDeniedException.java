package cn.teamone.shared.api;

import java.util.List;

/**
 * 授权链未通过（四步短路链默认拒绝）。HTTP 层按 {@link #errorCode()} 的 httpStatus 映射
 * （403 常态；401 仅用于切面里"上下文无主体"的防御分支）。
 *
 * @author Ivan Yang, 2026-09-11
 */
public class PermissionDeniedException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    public PermissionDeniedException(ErrorCode errorCode, String message, List<String> details) {
        super(message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : details;
    }

    public PermissionDeniedException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 错误码全文（如 T1-PLT-4030） */
    public String code() {
        return errorCode.code();
    }

    public List<String> details() {
        return details;
    }
}
