package com.smartticket.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 优先级枚举守护测试：取值集合必须与数据库 / 决策记录一致（不得擅自新增或改名）。
 */
class TicketPriorityTest {

    @Test
    @DisplayName("优先级取值与决策记录一致，且包含数据库默认值 MEDIUM")
    void priorityValuesMatchConfirmedSet() {
        Set<String> actual = Arrays.stream(TicketPriority.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("LOW", "MEDIUM", "HIGH", "URGENT"), actual,
                "优先级集合发生变化，必须同步数据库与决策记录");
    }

    @Test
    @DisplayName("每个优先级都有中文描述")
    void everyPriorityHasDescription() {
        for (TicketPriority priority : TicketPriority.values()) {
            assertFalse(priority.getDescription().isBlank(), priority.name() + " 缺少中文描述");
        }
    }

}
