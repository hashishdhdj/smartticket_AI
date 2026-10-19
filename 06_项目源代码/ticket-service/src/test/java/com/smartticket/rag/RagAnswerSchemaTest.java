package com.smartticket.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Schema 与 Java 侧定义的<b>一致性守护</b>（阶段2 关键约束第 2 条「必须定义 JSON Schema」）。
 *
 * <p>与 {@code AiClassificationSchemaTest} 同一套路，理由也一样：同一个契约有两份表达——
 * 给人看/给外部工具校验的 {@code rag-answer-result.schema.json}，和真正执行校验的
 * {@link RagAnswerParser} 常量。两份真值迟早漂移，而漂移的后果很隐蔽：
 * schema 说相似问题最多 5 条、代码里放宽到 8 条，谁也不会发现。</p>
 *
 * <p>本测试把"改动必须同步"从人工纪律变成机械检查。</p>
 */
class RagAnswerSchemaTest {

    private static final String SCHEMA_PATH = "prompts/rag-answer-result.schema.json";

    private static final JsonNode SCHEMA = loadSchema();

    @Test
    @DisplayName("schema 的字段集合 与 RagAnswerPayload 的 record 组件完全一致")
    void propertyNamesMatchRecordComponents() {
        List<String> recordComponents = Arrays.stream(RagAnswerPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .sorted()
                .collect(Collectors.toList());

        List<String> schemaProperties = new ArrayList<>();
        SCHEMA.path("properties").fieldNames().forEachRemaining(schemaProperties::add);
        schemaProperties.sort(null);

        assertEquals(recordComponents, schemaProperties,
                "schema 的字段集与 record 组件不一致（改了字段名没同步 schema，或反之）");
    }

    @Test
    @DisplayName("schema 的 required 覆盖全部 3 个字段")
    void allFieldsAreRequired() {
        List<String> required = stringList(SCHEMA.path("required"));

        assertEquals(3, required.size(), "三个字段应全部必填");
        assertTrue(required.containsAll(List.of("suggestion", "similarQuestions", "needHuman")));
    }

    @Test
    @DisplayName("schema 关闭 additionalProperties，对应解析器拒绝未知字段的策略")
    void additionalPropertiesIsClosed() {
        // 两处必须同向：schema 说不允许额外字段，解析器也要真的拒绝，
        // 否则 schema 只是一份好看但没人执行的文档
        assertFalse(SCHEMA.path("additionalProperties").asBoolean(true),
                "schema 必须关闭 additionalProperties");
    }

    @Test
    @DisplayName("schema 的长度与数量上限 与 解析器常量一致")
    void lengthBoundsMatchConstants() {
        assertEquals(RagAnswerParser.MAX_SUGGESTION_LENGTH,
                SCHEMA.at("/properties/suggestion/maxLength").asInt(),
                "suggestion 长度上限不一致");

        assertEquals(RagAnswerParser.MAX_QUESTIONS,
                SCHEMA.at("/properties/similarQuestions/maxItems").asInt(),
                "相似问题数量上限不一致");

        assertEquals(RagAnswerParser.MAX_QUESTION_LENGTH,
                SCHEMA.at("/properties/similarQuestions/items/maxLength").asInt(),
                "单条相似问题长度上限不一致");
    }

    @Test
    @DisplayName("schema 的下界为 1（不允许空串与空数组），与解析器「缺失或为空即拒绝」的策略一致")
    void lowerBoundsAreOne() {
        assertEquals(1, SCHEMA.at("/properties/suggestion/minLength").asInt(),
                "suggestion 不允许空串");
        assertEquals(1, SCHEMA.at("/properties/similarQuestions/minItems").asInt(),
                "similarQuestions 不允许空数组——章程要求必须返回相似问题");
        assertEquals(1, SCHEMA.at("/properties/similarQuestions/items/minLength").asInt(),
                "相似问题不允许空串");
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
