package com.smartticket.enums;

import com.smartticket.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机单元测试。
 *
 * <p>纯内存逻辑：不启动 Spring、不连数据库，可毫秒级穷举全部 6×6=36 种组合。</p>
 *
 * <p>期望矩阵在测试里<b>独立写一份</b>，而不是复用实现里的集合——
 * 否则"用实现测实现"，实现写错时测试也会跟着错，等于没测。</p>
 */
class TicketStatusTest {

    /** 与决策记录 20260917_工单状态机.md 完全一致的期望流转矩阵 */
    private static final Map<TicketStatus, Set<TicketStatus>> EXPECTED_TRANSITIONS =
            new EnumMap<>(TicketStatus.class);

    static {
        EXPECTED_TRANSITIONS.put(TicketStatus.PENDING_ASSIGN,
                EnumSet.of(TicketStatus.PROCESSING, TicketStatus.SUSPENDED, TicketStatus.CLOSED));
        EXPECTED_TRANSITIONS.put(TicketStatus.PROCESSING,
                EnumSet.of(TicketStatus.PENDING_FEEDBACK, TicketStatus.RESOLVED,
                        TicketStatus.SUSPENDED, TicketStatus.PENDING_ASSIGN));
        EXPECTED_TRANSITIONS.put(TicketStatus.PENDING_FEEDBACK,
                EnumSet.of(TicketStatus.PROCESSING, TicketStatus.RESOLVED, TicketStatus.SUSPENDED));
        EXPECTED_TRANSITIONS.put(TicketStatus.RESOLVED,
                EnumSet.of(TicketStatus.CLOSED, TicketStatus.PROCESSING));
        EXPECTED_TRANSITIONS.put(TicketStatus.SUSPENDED,
                EnumSet.of(TicketStatus.PROCESSING));
        EXPECTED_TRANSITIONS.put(TicketStatus.CLOSED,
                EnumSet.of(TicketStatus.PROCESSING));
    }

    @Test
    @DisplayName("状态取值集合必须与数据库/决策记录一致，禁止擅自新增状态")
    void statusValuesMustMatchConfirmedSet() {
        Set<String> actual = Arrays.stream(TicketStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("PENDING_ASSIGN", "PROCESSING", "PENDING_FEEDBACK", "RESOLVED", "CLOSED", "SUSPENDED"),
                actual,
                "状态集合发生变化，必须同步数据库与决策记录");
    }

    @Test
    @DisplayName("穷举 36 种组合：白名单内通过，其余全部抛业务异常")
    void allThirtySixCombinationsMatchExpectedMatrix() {
        int allowedEdges = 0;
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus to : TicketStatus.values()) {
                boolean expectedAllowed = EXPECTED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
                String edge = from.name() + " → " + to.name();

                assertEquals(expectedAllowed, from.canTransitionTo(to), edge + " 的判定与期望矩阵不一致");

                if (expectedAllowed) {
                    assertDoesNotThrow(() -> from.checkTransitionTo(to), edge + " 应被允许");
                    allowedEdges++;
                } else {
                    assertThrows(BusinessException.class, () -> from.checkTransitionTo(to), edge + " 应被拒绝");
                }
            }
        }
        assertEquals(14, allowedEdges, "白名单边数应为 14（3+4+3+2+1+1）");
    }

    @Test
    @DisplayName("自身流转一律拒绝（避免无意义的状态变更污染操作日志）")
    void selfTransitionIsAlwaysRejected() {
        for (TicketStatus status : TicketStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " 不应允许流转到自身");
            assertThrows(BusinessException.class, () -> status.checkTransitionTo(status));
        }
    }

    @Test
    @DisplayName("已关闭可重开：CLOSED → PROCESSING（2026-09-17 决策）")
    void closedTicketCanBeReopened() {
        assertTrue(TicketStatus.CLOSED.canTransitionTo(TicketStatus.PROCESSING));
        assertDoesNotThrow(() -> TicketStatus.CLOSED.checkTransitionTo(TicketStatus.PROCESSING));
    }

    @Test
    @DisplayName("非法流转抛业务异常：错误码 10001，提示含起止状态")
    void illegalTransitionThrowsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> TicketStatus.PENDING_ASSIGN.checkTransitionTo(TicketStatus.RESOLVED));

        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION, ex.getErrorCode());
        assertEquals(10001, ex.getErrorCode().getCode());
        assertTrue(ex.getMessage().contains("待分配"), "提示应含起始状态");
        assertTrue(ex.getMessage().contains("已解决"), "提示应含目标状态");
    }

    @Test
    @DisplayName("目标状态为 null 时被拒绝，而不是抛空指针")
    void nullTargetIsRejectedNotNpe() {
        assertFalse(TicketStatus.PROCESSING.canTransitionTo(null));
        assertThrows(BusinessException.class, () -> TicketStatus.PROCESSING.checkTransitionTo(null));
    }

    @Test
    @DisplayName("fromCode 能按数据库中的值反査枚举")
    void fromCodeResolvesKnownValue() {
        assertEquals(TicketStatus.PENDING_ASSIGN, TicketStatus.fromCode("PENDING_ASSIGN"));
        assertEquals(TicketStatus.CLOSED, TicketStatus.fromCode("CLOSED"));
    }

    @Test
    @DisplayName("fromCode 遇到空值或未知值抛业务异常：错误码 10002")
    void fromCodeRejectsNullBlankAndUnknown() {
        for (String badValue : new String[]{null, "", "   ", "UNKNOWN_VALUE", "pending_assign"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> TicketStatus.fromCode(badValue),
                    "值 [" + badValue + "] 应被拒绝");
            assertEquals(ErrorCode.UNKNOWN_STATUS_CODE, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("白名单集合只读，外部无法篡改流转规则")
    void allowedNextStatusesIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> TicketStatus.PENDING_ASSIGN.allowedNextStatuses().add(TicketStatus.RESOLVED));
    }

}
