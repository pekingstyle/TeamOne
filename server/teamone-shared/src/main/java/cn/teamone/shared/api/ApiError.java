package cn.teamone.shared.api;

import java.util.List;

/** 统一错误信封：{ code, message, details, traceId }，code 规范见架构设计 §3.2 与 {@link ErrorCode} */
public record ApiError(String code, String message, List<String> details, String traceId) {
    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, details == null ? List.of() : details, null);
    }

    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }

    /** 推荐入口：错误码必须来自 {@link ErrorCode} 目录，禁止手写字符串 */
    public static ApiError of(ErrorCode ec, String message, List<String> details) {
        return of(ec.code(), message == null ? ec.defaultMessage() : message, details);
    }

    public static ApiError of(ErrorCode ec, String message) {
        return of(ec, message, List.of());
    }

    public static ApiError of(ErrorCode ec) {
        return of(ec, ec.defaultMessage(), List.of());
    }
}
