package com.smartticket.exception;

import com.smartticket.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出，由 {@link GlobalExceptionHandler} 统一翻译为统一返回体。
 *
 * <p>继承 {@link RuntimeException}（非受检），这样业务代码不需要层层声明 throws，
 * 也不用写 try-catch 去转换返回值。</p>
 *
 * <p>注意：本异常是"可预期的失败"，日志用 WARN 级别记录、且不打堆栈；
 * 只有真正的系统异常才会用 ERROR + 堆栈。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对应的错误码 */
    private final ErrorCode errorCode;

    /** 使用错误码自带文案 */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 使用更具体的自定义文案（推荐：把"哪里不对"说清楚） */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

}
