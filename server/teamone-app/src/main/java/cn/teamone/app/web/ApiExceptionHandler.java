package cn.teamone.app.web;

import cn.teamone.shared.api.ApiError;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 统一异常出口：全部错误信封走 {@link ErrorCode} 目录。
 * 权限异常按其 errorCode 的 httpStatus 映射（403 常态）；未知异常 5xxx 只回 traceId。
 *
 * @author Ivan Yang, 2026-09-11
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiError> denied(PermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.valueOf(e.errorCode().httpStatus()))
                .body(ApiError.of(e.errorCode(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.PLT_4000, "参数校验失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        String traceId = java.util.UUID.randomUUID().toString();
        log.error("[{}] unexpected error", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.SRV_5000, null, java.util.List.of(traceId)));
    }
}
