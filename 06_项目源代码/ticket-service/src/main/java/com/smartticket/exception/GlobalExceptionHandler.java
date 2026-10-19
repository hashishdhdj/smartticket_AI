package com.smartticket.exception;

import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Single MVC error boundary. Security filters use the same Result factories. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    public ResponseEntity<Result<Void>> apiFailure(ApiFailure failure) {
        return ResponseEntity.status(failure.status()).body(Result.fail(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException failure) {
        log.warn("Business failure | code={}", failure.getErrorCode().getCode());
        return ResponseEntity.status(status(failure.getErrorCode()))
                .body(Result.fail(failure.getErrorCode(), failure.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> binding(BindException failure) {
        String detail = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> constraints(ConstraintViolationException failure) {
        String detail = failure.getConstraintViolations().stream()
                .map(error -> error.getPropertyPath() + ": " + error.getMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.REQUEST_BODY_UNREADABLE));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> invalidType(Exception failure) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception failure) {
        // Spring's routing, method, binding and media-type failures retain their HTTP status/headers.
        // Never expose ProblemDetail detail, which can contain request or implementation data.
        if (failure instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            String message = switch (error.getStatusCode().value()) {
                case 404 -> "接口不存在";
                case 405 -> "请求方法不支持";
                case 415 -> "请求内容类型不支持";
                default -> "请求参数无效";
            };
            return new ResponseEntity<>(Result.fail(ErrorCode.PARAM_INVALID, message),
                    error.getHeaders(), error.getStatusCode());
        }
        log.error("Unhandled application failure", failure);
        return ResponseEntity.internalServerError().body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    private static int status(ErrorCode code) {
        return switch (code) {
            case TICKET_NOT_FOUND -> 404;
            case INVALID_STATUS_TRANSITION, TICKET_NO_CONFLICT, TICKET_STATUS_NOT_ALLOWED,
                    VECTOR_MODEL_MISMATCH -> 409;
            case AI_NOT_CONFIGURED, AI_CALL_FAILED, VECTOR_STORE_UNAVAILABLE, AI_AUTH_FAILED -> 503;
            case AI_RESPONSE_UNPARSABLE -> 502;
            case AI_BUDGET_EXHAUSTED -> 429;
            case SYSTEM_ERROR -> 500;
            default -> 400;
        };
    }
}
