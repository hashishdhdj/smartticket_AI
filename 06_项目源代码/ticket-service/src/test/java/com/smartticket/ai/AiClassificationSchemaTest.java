package com.smartticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.enums.TicketCategory;
import com.smartticket.enums.TicketPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Schema 与 Java 侧定义的<b>一致性守护</b>。
 *
 * <p>为什么值得单独写一个测试类：同一个契约现在有两份表达——给人看/给厂商看的
 * {@code ai-classification-result.schema.json}，和真正执行校验的
 * {@link AiClassificationParser} 的常量与枚举。两份真值迟早会漂移，
 * 而漂移的后果很隐蔽：JSON Schema 说 keywords 最多 8 个，代码里放宽到 10 个，
 * 谁也不会发现，直到某天模型返回 9 个关键词、schema 校验（若将来启用）与运行时行为不一致。</p>
 *
 * <p>本测试把"改动必须同步"从人工纪律变成机械检查。它是阶段2 约束
 * 「必须定义 JSON Schema」的第二半句——只定义、不守护，等于没定义。</p>
 */
class AiClassificationSchemaTest {

    private static final String SCHEMA_PATH = "prompts/ai-classification-result.schema.json";

    private static final JsonNode SCHEMA = loadSchema();

    @Test
    @DisplayName("schema 的 category 枚举 与 TicketCategory 完全一致（含顺序）")
    void categoryEnumMatchesEnum() {
        assertEquals(TicketCategory.allLabels(), stringList(SCHEMA.at("/properties/category/enum")),
                "schema 的分类清单与 TicketCategory 不一致，两边必须同步修改");
    }

    @Test
    @DisplayName("schema 的 priority 枚举 与 TicketPriority 完全一致（含顺序）")
    void priorityEnumMatchesEnum() {
        List<String> javaValues = Arrays.stream(TicketPriority.values())
                .map(Enum::name)
                .collect(Collectors.toList());

        assertEquals(javaValues, stringList(SCHEMA.at("/properties/priority/enum")),
                "schema 的优先级清单与 TicketPriority 不一致");
    }

    @Test
    @DisplayName("schema 的字段集合 与 AiClassificationResult 的 record 组件完全一致")
    void propertyNamesMatchRecordComponents() {
        List<String> recordComponents = Arrays.stream(AiClassificationResult.class.getRecordComponents())
                .map(component -> component.getName())
                .sorted()
                .collect(Collectors.toList());

        List<String> schemaProperties = new ArrayList<>();
        SCHEMA.path("properties").fieldNames().forEachRemaining(schemaProperties::add);
        schemaProperties.sort(null);

        assertEquals(recordComponents, schemaProperties,
                "schema 的字段集与 record 组件不一致（改了字段名没同步 schema，或反之）");
    }

    @Test
    @DisplayName("schema 的 required 覆盖全部 5 个字段")
    void allFieldsAreRequired() {
        List<String> required = stringList(SCHEMA.path("required"));

        assertEquals(5, required.size(), "5 个字段应全部必填");
        assertTrue(required.containsAll(List.of("category", "priority", "confidence", "keywords", "reason")));
    }

    @Test
    @DisplayName("schema 的 additionalProperties 为 false，对应解析器的拒绝未知字段策略")
    void additionalPropertiesIsClosed() {
        // 这两处必须同向：schema 说"不允许额外字段"，解析器也要真的拒绝，
        // 否则 schema 只是一份好看但没人执行的文档
        assertFalse(SCHEMA.path("additionalProperties").asBoolean(true),
                "schema 必须关闭 additionalProperties");
    }

    @Test
    @DisplayName("schema 的长度与数量上限 与 解析器常量一致")
    void lengthBoundsMatchConstants() {
        assertEquals(AiClassificationParser.MAX_KEYWORDS,
                SCHEMA.at("/properties/keywords/maxItems").asInt(),
                "keywords 数量上限不一致");

        assertEquals(AiClassificationParser.MAX_KEYWORD_LENGTH,
                SCHEMA.at("/properties/keywords/items/maxLength").asInt(),
                "单个关键词长度上限不一致");

        assertEquals(AiClassificationParser.MAX_REASON_LENGTH,
                SCHEMA.at("/properties/reason/maxLength").asInt(),
                "reason 长度上限不一致");

        assertEquals(0, SCHEMA.at("/properties/confidence/minimum").asDouble(), "confidence 下界不一致");
        assertEquals(1, SCHEMA.at("/properties/confidence/maximum").asDouble(), "confidence 上界不一致");
    }

    // ------------------------------------------------------------ 工具

    private static JsonNode loadSchema() {
        try (InputStream in = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JSON Schema 失败：" + SCHEMA_PATH, e);
        }
    }

    private List<String> stringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

}
