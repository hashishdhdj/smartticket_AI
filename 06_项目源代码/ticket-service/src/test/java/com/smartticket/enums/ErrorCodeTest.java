package com.smartticket.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码守护测试。
 *
 * <p>背景：Java 枚举允许两个常量取相同的码值而编译器<b>不报错</b>，
 * 本项目在阶段1第5天真实出现过「10001 被定义两次」的情况。此测试用于在构建期拦住这类问题。</p>
 */
class ErrorCodeTest {

    @Test
    @DisplayName("码值不允许重复（重复定义编译期不报错，只能靠测试守）")
    void codesMustBeUnique() {
        Map<Integer, List<String>> grouped = Arrays.stream(ErrorCode.values())
                .collect(Collectors.groupingBy(ErrorCode::getCode,
                        Collectors.mapping(Enum::name, Collectors.toList())));

        grouped.forEach((code, names) -> assertEquals(1, names.size(),
                "错误码 " + code + " 被重复定义：" + names));
    }

    @Test
    @DisplayName("成功码固定为 0")
    void successCodeIsZero() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
    }

    @Test
    @DisplayName("非成功码必须落在约定分段内：1xxxx 业务 / 4xxxx 客户端 / 5xxxx 系统")
    void codesFallIntoConfirmedSegments() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode == ErrorCode.SUCCESS) {
                continue;
            }
            int code = errorCode.getCode();
            boolean inSegment = (code >= 10000 && code <= 19999)
                    || (code >= 40000 && code <= 49999)
                    || (code >= 50000 && code <= 59999);
            assertTrue(inSegment, errorCode.name() + " 的码值 " + code + " 不在约定分段内");
        }
    }

    @Test
    @DisplayName("每个错误码都有非空文案")
    void everyCodeHasMessage() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertTrue(errorCode.getMessage() != null && !errorCode.getMessage().isBlank(),
                    errorCode.name() + " 缺少文案");
        }
    }

}
