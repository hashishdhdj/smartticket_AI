package com.smartticket.common;

import com.smartticket.enums.ErrorCode;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 统一返回体：所有接口无论成功或失败，对外都是 {@code {code, message, data}} 这一种结构。
 *
 * <p>约定（2026-09-16 决策）：</p>
 * <ul>
 *   <li>{@code code = 0} 表示成功，非 0 见 {@link ErrorCode}；</li>
 *   <li>成功及明确的业务降级使用 HTTP 200；异常同时使用对应 HTTP 状态和业务码；</li>
 *   <li>{@code data} 仅在成功且有数据时出现——项目启用
 *       {@code spring.jackson.default-property-inclusion=non_null}，null 字段不会输出。</li>
 * </ul>
 *
 * <p>对象不可变：字段全为 final，只提供静态工厂方法，避免被拼出不合法的组合。</p>
 *
 * @param <T> 业务数据类型
 */
@Getter
@ToString
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码：0 表示成功 */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 业务数据，失败时为 null */
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功，无数据 */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 成功，携带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 失败，使用错误码自带的文案 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败，使用自定义文案（例如业务异常中更具体的说明） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** Shared by MVC exception handling and the security filter. */
    public static <T> Result<T> fail(int code, String message) {
        if (code == 0) throw new IllegalArgumentException("Failure code must be nonzero");
        return new Result<>(code, message, null);
    }

}
