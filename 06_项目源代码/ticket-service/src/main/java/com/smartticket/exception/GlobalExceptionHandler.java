package com.smartticket.exception;

import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有未在业务代码中处理的异常，都在这里被翻译成统一返回体。
 *
 * <p>处理原则：</p>
 * <ul>
 *   <li>可预期的失败（业务异常、参数校验）用 WARN 记录，不打堆栈；</li>
 *   <li>未预料的异常（兜底）用 ERROR 记录并打印堆栈，但<b>对外只返回固定文案</b>，
 *       绝不把异常信息、SQL、连接串等内部细节暴露给调用方。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：使用异常携带的错误码与文案 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常 | code={} | message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 参数校验失败。
     *
     * <p>一个处理器覆盖两种来源：{@code @RequestBody @Valid} 抛出的
     * {@code MethodArgumentNotValidException} 与表单绑定的 {@code BindException}，
     * 因为前者继承自后者（已用 javap 核实 Spring 6.1.21 的继承关系）。</p>
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 | {}", detail);
        return Result.fail(ErrorCode.PARAM_INVALID, detail);
    }

    /** 方法级参数校验失败（类上标 @Validated，方法参数上标约束注解时触发） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 | {}", detail);
        return Result.fail(ErrorCode.PARAM_INVALID, detail);
    }

    /** 请求体无法解析：JSON 格式错误、类型不匹配等 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return Result.fail(ErrorCode.REQUEST_BODY_UNREADABLE);
    }

    /** 兜底：未预料的系统异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /** 把字段错误格式化为 {@code 字段名: 提示}，便于前端直接展示或定位 */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

}
