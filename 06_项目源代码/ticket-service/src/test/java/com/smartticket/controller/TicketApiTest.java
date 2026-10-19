package com.smartticket.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.enums.TicketPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第6天验收：工单 5 个接口的集成测试。
 *
 * <p>用 {@code @SpringBootTest + @AutoConfigureMockMvc} 走真实 MySQL，
 * 覆盖 Controller → Service → Mapper → 状态机/SQL 全链路（不是切片、不是 mock）。</p>
 *
 * <p>测试数据用固定 creatorId 标识，并在 {@code @AfterEach} 中<b>物理删除</b>：
 * 业务代码走逻辑删除（@TableLogic），deleteById 只会把 deleted 置 1；
 * 残留数据会污染其他测试对"表为空"的断言，所以这里用 JdbcTemplate 直接 DELETE。</p>
 *
 * <p>断言说明：过滤型 JsonPath（{@code [?(...)]}）配合 {@code exists()} 在结果为空时
 * 也会通过，属于"假通过"，因此这里统一改用 {@code records[*].title} + Hamcrest 的
 * {@code hasItem / not(hasItem) / everyItem} 表达"包含/不包含/全部满足"。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TicketApiTest {

    /** 测试专用创建人ID，用于隔离与清理测试数据 */
    private static final long TEST_CREATOR_ID = 999_999L;

    /** 测试专用操作人ID */
    private static final long TEST_OPERATOR_ID = 888_888L;

    /** 测试专用处理人ID */
    private static final long TEST_ASSIGNEE_ID = 777_777L;

    /** 不存在的工单ID */
    private static final long UNKNOWN_TICKET_ID = 999_999_999L;

    /** 详情缓存 key 前缀，与 TicketServiceImpl.CACHE_KEY_DETAIL_PREFIX 保持一致 */
    private static final String DETAIL_CACHE_KEY_PREFIX = "ticket:detail:";

    /** 工单编号规则：ST-yyyyMMdd-HHmmss-4位十六进制 */
    private static final Pattern TICKET_NO_PATTERN = Pattern.compile("^ST-\\d{8}-\\d{6}-[0-9A-F]{4}$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUpTestData() {
        // 先清缓存、再删数据：第9天起详情查询会写缓存（ticket:detail:{id}），
        // 只删数据库行会留下指向"已不存在工单"的缓存垃圾，要等 TTL 到期才消失
        jdbcTemplate.queryForList("SELECT id FROM ticket WHERE creator_id = ?", Long.class, TEST_CREATOR_ID)
                .forEach(ticketId -> stringRedisTemplate.delete(DETAIL_CACHE_KEY_PREFIX + ticketId));

        jdbcTemplate.update(
                "DELETE FROM ticket_log WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id = ?)",
                TEST_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM ticket WHERE creator_id = ?", TEST_CREATOR_ID);
    }

    // ------------------------------------------------------------- 接口1：创建

    @Test
    @DisplayName("创建工单：统一返回格式、编号符合规则、状态固定为待分配、内部字段不外泄")
    void createTicketReturnsExpectedPayload() throws Exception {
        JsonNode data = createTicket("创建测试-打印机无法连接", "HIGH");

        assertTrue(data.get("id").asLong() > 0, "id 应由数据库自增回填");
        String ticketNo = data.get("ticketNo").asText();
        assertTrue(TICKET_NO_PATTERN.matcher(ticketNo).matches(), "工单编号格式不符：" + ticketNo);
        assertEquals("PENDING_ASSIGN", data.get("status").asText(), "创建时状态必须固定为待分配");
        assertEquals("HIGH", data.get("priority").asText());
        assertEquals(TEST_CREATOR_ID, data.get("creatorId").asLong());
        assertTrue(!data.get("createdAt").asText().isEmpty(), "createdAt 应由数据库默认值填充");
        // 注意：get("缺失字段") 返回 Java null，不是 MissingNode（MissingNode 仅用于 JSON 显式 null）
        assertTrue(!data.has("deleted"), "deleted 属内部字段，不应返回给前端");
    }

    @Test
    @DisplayName("创建工单：不传优先级时取数据库默认值 MEDIUM")
    void createTicketWithoutPriorityFallsBackToMedium() throws Exception {
        JsonNode data = createTicket("创建测试-不传优先级", null);
        assertEquals(TicketPriority.MEDIUM.name(), data.get("priority").asText());
    }

    @Test
    @DisplayName("创建工单：标题为空白返回参数错误码 40000，且提示含字段名")
    void createTicketWithBlankTitleReturnsParamInvalid() throws Exception {
        Map<String, Object> body = baseCreateBody();
        body.put("title", "   ");

        mockMvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("title")));
    }

    @Test
    @DisplayName("创建工单：缺少创建人返回参数错误码 40000")
    void createTicketWithoutCreatorIdReturnsParamInvalid() throws Exception {
        Map<String, Object> body = baseCreateBody();
        body.remove("creatorId");

        mockMvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("creatorId")));
    }

    @Test
    @DisplayName("创建工单：标题正好 200 字符通过（@Size 边界值内）")
    void createTicketWithMaxLengthTitleSucceeds() throws Exception {
        String title = "标".repeat(200);

        JsonNode data = createTicket(title, "LOW");

        assertEquals(200, data.get("title").asText().length(), "边界值内的标题应被接受并原样返回");
    }

    @Test
    @DisplayName("创建工单：标题 201 字符被拒绝返回 40000（@Size 边界值外）")
    void createTicketWithOverlongTitleReturnsParamInvalid() throws Exception {
        Map<String, Object> body = baseCreateBody();
        body.put("title", "标".repeat(201));

        mockMvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("title")));
    }

    @Test
    @DisplayName("创建工单：连续创建 3 条，工单编号互不相同")
    void createdTicketNosAreUnique() throws Exception {
        Set<String> ticketNos = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ticketNos.add(createTicket("创建测试-编号唯一性-" + i, "LOW").get("ticketNo").asText());
        }
        assertEquals(3, ticketNos.size(), "生成的工单编号出现重复：" + ticketNos);
    }

    // ------------------------------------------------------------- 接口2：详情

    @Test
    @DisplayName("查询详情：能查到刚创建的工单")
    void getByIdReturnsCreatedTicket() throws Exception {
        JsonNode created = createTicket("详情测试-会议室投屏失败", "URGENT");
        long id = created.get("id").asLong();

        mockMvc.perform(get("/api/tickets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.title").value("详情测试-会议室投屏失败"))
                .andExpect(jsonPath("$.data.deleted").doesNotExist());
    }

    @Test
    @DisplayName("查询详情：不存在的工单返回业务错误码 10003")
    void getByIdWithUnknownIdReturnsTicketNotFound() throws Exception {
        mockMvc.perform(get("/api/tickets/{id}", UNKNOWN_TICKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value(containsString("999999999")));
    }

    // ------------------------------------------------------------- 接口3：分页

    @Test
    @DisplayName("分页查询：返回 PageVO 结构；status 筛选真的生效（含/不含双向验证）")
    void pageReturnsRecordsAndSupportsStatusFilter() throws Exception {
        createTicket("分页测试-账号被锁定", "MEDIUM");
        insertTicketDirectly("分页测试-已关闭的工单", "CLOSED");

        // 不筛选：两条测试数据都应出现
        mockMvc.perform(get("/api/tickets").param("current", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.pages").isNumber())
                .andExpect(jsonPath("$.data.records[*].title").value(hasItem("分页测试-账号被锁定")))
                .andExpect(jsonPath("$.data.records[*].title").value(hasItem("分页测试-已关闭的工单")));

        // 筛选 PENDING_ASSIGN：必须有待分配那条，且绝不能出现已关闭那条
        mockMvc.perform(get("/api/tickets").param("status", "PENDING_ASSIGN").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].title").value(hasItem("分页测试-账号被锁定")))
                .andExpect(jsonPath("$.data.records[*].title").value(not(hasItem("分页测试-已关闭的工单"))))
                .andExpect(jsonPath("$.data.records[*].status").value(everyItem(is("PENDING_ASSIGN"))));

        // 筛选 CLOSED：必须只包含已关闭那条
        mockMvc.perform(get("/api/tickets").param("status", "CLOSED").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].title").value(hasItem("分页测试-已关闭的工单")))
                .andExpect(jsonPath("$.data.records[*].title").value(not(hasItem("分页测试-账号被锁定"))))
                .andExpect(jsonPath("$.data.records[*].status").value(everyItem(is("CLOSED"))));
    }

    @Test
    @DisplayName("分页查询：size 超过上限 100 返回参数错误码 40000")
    void pageWithTooLargeSizeReturnsParamInvalid() throws Exception {
        mockMvc.perform(get("/api/tickets").param("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("分页查询：非法状态值返回业务错误码 10002（复用状态机取值校验）")
    void pageWithUnknownStatusReturnsUnknownStatusCode() throws Exception {
        mockMvc.perform(get("/api/tickets").param("status", "NOT_A_STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002));
    }

    // ------------------------------------------------------------- 接口4：更新

    @Test
    @DisplayName("更新工单：可改字段生效、可清空字段、状态不被修改（多传 status 也被忽略）")
    void updateChangesEditableFieldsAndKeepsStatus() throws Exception {
        JsonNode created = createTicket("更新测试-原标题", "LOW");
        long id = created.get("id").asLong();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "更新测试-新标题");
        body.put("description", null);      // 主动清空
        body.put("category", "软件使用");
        body.put("priority", "URGENT");
        body.put("tags", null);             // 主动清空
        body.put("operatorId", TEST_OPERATOR_ID);
        body.put("status", "CLOSED");       // 越权字段：DTO 里没有，必须被忽略

        mockMvc.perform(put("/api/tickets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("更新测试-新标题"))
                .andExpect(jsonPath("$.data.priority").value("URGENT"))
                .andExpect(jsonPath("$.data.category").value("软件使用"))
                .andExpect(jsonPath("$.data.status").value("PENDING_ASSIGN"))
                .andExpect(jsonPath("$.data.description").doesNotExist())
                .andExpect(jsonPath("$.data.tags").doesNotExist());
    }

    @Test
    @DisplayName("更新工单：优先级为空返回参数错误码 40000")
    void updateWithoutPriorityReturnsParamInvalid() throws Exception {
        JsonNode created = createTicket("更新测试-缺少优先级", "LOW");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "只改标题");
        body.put("operatorId", TEST_OPERATOR_ID);

        mockMvc.perform(put("/api/tickets/{id}", created.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("priority")));
    }

    @Test
    @DisplayName("更新工单：工单不存在返回业务错误码 10003")
    void updateUnknownTicketReturnsTicketNotFound() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "改一个不存在的工单");
        body.put("priority", "LOW");
        body.put("operatorId", TEST_OPERATOR_ID);

        mockMvc.perform(put("/api/tickets/{id}", UNKNOWN_TICKET_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ------------------------------------------------------------- 接口5：日志

    @Test
    @DisplayName("查询日志：创建后应有一条 CREATE 记录，含操作人与状态流转")
    void listLogsContainsCreateRecord() throws Exception {
        JsonNode created = createTicket("日志测试-键盘失灵", "MEDIUM");
        long id = created.get("id").asLong();

        mockMvc.perform(get("/api/tickets/{id}/logs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ticketId").value(id))
                .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[0].operatorId").value(TEST_CREATOR_ID))
                .andExpect(jsonPath("$.data[0].toStatus").value("PENDING_ASSIGN"))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("查询日志：工单不存在返回业务错误码 10003")
    void listLogsForUnknownTicketReturnsTicketNotFound() throws Exception {
        mockMvc.perform(get("/api/tickets/{id}/logs", UNKNOWN_TICKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ------------------------------------------------------- 接口6：指派工单

    @Test
    @DisplayName("指派工单：待分配 → 流转到处理中，写入处理人并记录 ASSIGN 日志")
    void assignPendingTicketMovesToProcessing() throws Exception {
        long id = createTicket("指派测试-待分配工单", "MEDIUM").get("id").asLong();

        JsonNode response = assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);

        assertEquals(0, response.get("code").asInt());
        assertEquals("PROCESSING", response.get("data").get("status").asText(), "指派后应流转到处理中");
        assertEquals(TEST_ASSIGNEE_ID, response.get("data").get("assigneeId").asLong());

        JsonNode logs = logsOf(id);
        assertEquals(2, logs.size(), "应有 CREATE + ASSIGN 两条日志");
        JsonNode assignLog = logs.get(1);
        assertEquals("ASSIGN", assignLog.get("action").asText());
        assertEquals("PENDING_ASSIGN", assignLog.get("fromStatus").asText());
        assertEquals("PROCESSING", assignLog.get("toStatus").asText());
        assertEquals(TEST_OPERATOR_ID, assignLog.get("operatorId").asLong());
    }

    @Test
    @DisplayName("指派工单：处理中再次指派视为转派，只换处理人、状态不变")
    void assignProcessingTicketOnlyChangesAssignee() throws Exception {
        long id = createTicket("指派测试-转派", "MEDIUM").get("id").asLong();
        assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);

        JsonNode response = assignTicket(id, 666_666L, TEST_OPERATOR_ID);

        assertEquals("PROCESSING", response.get("data").get("status").asText(), "转派不应改变状态");
        assertEquals(666_666L, response.get("data").get("assigneeId").asLong());

        JsonNode transferLog = logsOf(id).get(2);
        assertEquals("ASSIGN", transferLog.get("action").asText());
        assertEquals("PROCESSING", transferLog.get("fromStatus").asText());
        assertEquals("PROCESSING", transferLog.get("toStatus").asText());
    }

    @Test
    @DisplayName("指派工单：已关闭工单被拒绝，返回业务错误码 10005")
    void assignClosedTicketIsRejected() throws Exception {
        long id = createTicket("指派测试-已关闭", "LOW").get("id").asLong();
        closeTicket(id, TEST_OPERATOR_ID);

        JsonNode response = assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);

        assertEquals(10005, response.get("code").asInt(), "已关闭工单不应被指派（避免隐式重开）");
    }

    @Test
    @DisplayName("指派工单：缺少处理人返回参数错误码 40000")
    void assignWithoutAssigneeReturnsParamInvalid() throws Exception {
        long id = createTicket("指派测试-缺处理人", "LOW").get("id").asLong();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operatorId", TEST_OPERATOR_ID);

        mockMvc.perform(post("/api/tickets/{id}/assign", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("assigneeId")));
    }

    @Test
    @DisplayName("指派工单：工单不存在返回业务错误码 10003")
    void assignUnknownTicketReturnsTicketNotFound() throws Exception {
        JsonNode response = assignTicket(UNKNOWN_TICKET_ID, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);
        assertEquals(10003, response.get("code").asInt());
    }

    // ------------------------------------------------------- 接口7：关闭工单

    @Test
    @DisplayName("关闭工单：待分配可直接关闭，并记录 CLOSE 日志")
    void closePendingTicketSucceeds() throws Exception {
        long id = createTicket("关闭测试-待分配", "LOW").get("id").asLong();

        JsonNode response = closeTicket(id, TEST_OPERATOR_ID);

        assertEquals(0, response.get("code").asInt());
        assertEquals("CLOSED", response.get("data").get("status").asText());

        JsonNode closeLog = logsOf(id).get(1);
        assertEquals("CLOSE", closeLog.get("action").asText());
        assertEquals("PENDING_ASSIGN", closeLog.get("fromStatus").asText());
        assertEquals("CLOSED", closeLog.get("toStatus").asText());
    }

    @Test
    @DisplayName("关闭工单：处理中直接关闭被状态机拒绝，返回 10001（非法流转）")
    void closeProcessingTicketIsRejectedByStateMachine() throws Exception {
        long id = createTicket("关闭测试-处理中不能直接关", "LOW").get("id").asLong();
        assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);

        JsonNode response = closeTicket(id, TEST_OPERATOR_ID);

        assertEquals(10001, response.get("code").asInt(),
                "处理中 → 已关闭 不在流转白名单内，必须被拒绝");
        assertEquals("PROCESSING", getStatusOf(id), "被拒绝后状态不应发生变化");
    }

    @Test
    @DisplayName("关闭工单：重复关闭被拒绝（自身流转 CLOSED → CLOSED）")
    void closeTwiceIsRejectedByStateMachine() throws Exception {
        long id = createTicket("关闭测试-重复关闭", "LOW").get("id").asLong();
        closeTicket(id, TEST_OPERATOR_ID);

        JsonNode response = closeTicket(id, TEST_OPERATOR_ID);

        assertEquals(10001, response.get("code").asInt());
    }

    @Test
    @DisplayName("关闭工单：已解决的工单可关闭（矩阵中 RESOLVED → CLOSED 合法）")
    void closeResolvedTicketSucceeds() throws Exception {
        long id = insertTicketDirectly("关闭测试-已解决工单", "RESOLVED");

        JsonNode response = closeTicket(id, TEST_OPERATOR_ID);

        assertEquals(0, response.get("code").asInt());
        assertEquals("CLOSED", response.get("data").get("status").asText());

        JsonNode closeLog = logsOf(id).get(0);
        assertEquals("RESOLVED", closeLog.get("fromStatus").asText());
        assertEquals("CLOSED", closeLog.get("toStatus").asText());
    }

    @Test
    @DisplayName("关闭工单：缺少操作人返回参数错误码 40000")
    void closeWithoutOperatorReturnsParamInvalid() throws Exception {
        long id = createTicket("关闭测试-缺操作人", "LOW").get("id").asLong();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("remark", "缺少操作人");

        mockMvc.perform(post("/api/tickets/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("operatorId")));
    }

    @Test
    @DisplayName("关闭工单：工单不存在返回业务错误码 10003")
    void closeUnknownTicketReturnsTicketNotFound() throws Exception {
        JsonNode response = closeTicket(UNKNOWN_TICKET_ID, TEST_OPERATOR_ID);
        assertEquals(10003, response.get("code").asInt());
    }

    // ------------------------------------------------------------- 接口8：解决

    @Test
    @DisplayName("解决工单：处理中 → 已解决，并记录 RESOLVE 日志")
    void resolveProcessingTicketSucceeds() throws Exception {
        long id = createTicket("解决测试-处理中", "LOW").get("id").asLong();
        assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);

        JsonNode response = resolveTicket(id, TEST_OPERATOR_ID);

        assertEquals(0, response.get("code").asInt());
        assertEquals("RESOLVED", response.get("data").get("status").asText());

        JsonNode resolveLog = logsOf(id).get(2);
        assertEquals("RESOLVE", resolveLog.get("action").asText());
        assertEquals("PROCESSING", resolveLog.get("fromStatus").asText());
        assertEquals("RESOLVED", resolveLog.get("toStatus").asText());
    }

    @Test
    @DisplayName("解决工单：待分配直接解决属跳步，返回 10001")
    void resolvePendingTicketIsRejectedByStateMachine() throws Exception {
        long id = createTicket("解决测试-待分配不能直接解决", "LOW").get("id").asLong();

        JsonNode response = resolveTicket(id, TEST_OPERATOR_ID);

        assertEquals(10001, response.get("code").asInt(),
                "待分配 → 已解决 不在流转白名单内，必须被拒绝");
        assertEquals("PENDING_ASSIGN", getStatusOf(id), "被拒绝后状态不应发生变化");
    }

    @Test
    @DisplayName("解决工单：重复解决被拒绝（自身流转 RESOLVED → RESOLVED）")
    void resolveTwiceIsRejectedByStateMachine() throws Exception {
        long id = createTicket("解决测试-重复解决", "LOW").get("id").asLong();
        assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID);
        resolveTicket(id, TEST_OPERATOR_ID);

        JsonNode response = resolveTicket(id, TEST_OPERATOR_ID);

        assertEquals(10001, response.get("code").asInt());
    }

    @Test
    @DisplayName("完整生命周期：创建 → 指派 → 解决 → 关闭，四步全部通过真实接口完成")
    void fullLifecycleFromCreateToClose() throws Exception {
        // 本用例验证的闭环，是在补上 resolve 接口之后才成立的：
        // 此前 RESOLVED 状态无法通过任何接口到达，而「处理中 → 已关闭」又被状态机拒绝
        //（要求先经"解决"环节），导致处理中的工单无处可去（缺陷登记表 DEF-003）。
        long id = createTicket("生命周期-端到端", "HIGH").get("id").asLong();
        assertEquals("PENDING_ASSIGN", getStatusOf(id));

        assertEquals(0, assignTicket(id, TEST_ASSIGNEE_ID, TEST_OPERATOR_ID).get("code").asInt());
        assertEquals("PROCESSING", getStatusOf(id));

        assertEquals(0, resolveTicket(id, TEST_OPERATOR_ID).get("code").asInt());
        assertEquals("RESOLVED", getStatusOf(id));

        assertEquals(0, closeTicket(id, TEST_OPERATOR_ID).get("code").asInt());
        assertEquals("CLOSED", getStatusOf(id));

        // 四步操作留下四条日志，构成完整审计链
        JsonNode logs = logsOf(id);
        assertEquals(4, logs.size(), "创建/指派/解决/关闭 应各留一条日志");
        assertEquals("CREATE", logs.get(0).get("action").asText());
        assertEquals("ASSIGN", logs.get(1).get("action").asText());
        assertEquals("RESOLVE", logs.get(2).get("action").asText());
        assertEquals("CLOSE", logs.get(3).get("action").asText());
    }

    @Test
    @DisplayName("解决工单：缺少操作人返回参数错误码 40000")
    void resolveWithoutOperatorReturnsParamInvalid() throws Exception {
        long id = createTicket("解决测试-缺操作人", "LOW").get("id").asLong();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("remark", "缺少操作人");

        mockMvc.perform(post("/api/tickets/{id}/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("operatorId")));
    }

    @Test
    @DisplayName("解决工单：工单不存在返回业务错误码 10003")
    void resolveUnknownTicketReturnsTicketNotFound() throws Exception {
        JsonNode response = resolveTicket(UNKNOWN_TICKET_ID, TEST_OPERATOR_ID);
        assertEquals(10003, response.get("code").asInt());
    }

    // ---------------------------------------------------- 契约守护与数据清理

    @Test
    @DisplayName("DTO 优先级正则必须覆盖 TicketPriority 全部取值（防枚举与校验漂移）")
    void priorityPatternCoversEveryEnumValue() throws Exception {
        Field priorityField = TicketCreateDTO.class.getDeclaredField("priority");
        jakarta.validation.constraints.Pattern annotation =
                priorityField.getAnnotation(jakarta.validation.constraints.Pattern.class);
        assertNotNull(annotation, "TicketCreateDTO.priority 必须带 @Pattern 校验");

        Pattern compiled;
        try {
            compiled = Pattern.compile(annotation.regexp());
        } catch (PatternSyntaxException e) {
            throw new AssertionError("优先级正则不合法：" + annotation.regexp(), e);
        }

        for (TicketPriority priority : TicketPriority.values()) {
            Matcher matcher = compiled.matcher(priority.name());
            assertTrue(matcher.matches(), priority.name() + " 未被 DTO 校验正则接受，正则需同步更新");
        }
        assertTrue(!compiled.matcher("BOGUS").matches(), "非法优先级不应通过校验");
    }

    @Test
    @DisplayName("清理逻辑有效性：测试数据确实被物理删除，不会残留污染其他测试")
    void testDataIsPhysicallyRemoved() throws Exception {
        long id = createTicket("清理测试-临时工单", "LOW").get("id").asLong();
        assertEquals(1, countTicketRow(id), "创建后库中应有该行");

        cleanUpTestData();
        assertEquals(0, countTicketRow(id), "清理后不应残留（必须是物理删除）");
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 创建工单并返回响应中的 data 节点（同时断言统一返回格式） */
    private JsonNode createTicket(String title, String priority) throws Exception {
        Map<String, Object> body = baseCreateBody();
        body.put("title", title);
        if (priority == null) {
            body.remove("priority");
        } else {
            body.put("priority", priority);
        }

        MvcResult result = mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        return readJson(result).get("data");
    }

    /** 直插一条指定状态的数据（返回 id），用于验证"状态筛选生效"与"矩阵中的其它边" */
    private long insertTicketDirectly(String title, String status) {
        String ticketNo = "ST-TEST-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO ticket "
                        + "(ticket_no, title, description, category, priority, status, creator_id, tags) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ticketNo, title, "直插测试数据", "其他", "LOW", status, TEST_CREATOR_ID, null);

        Long id = jdbcTemplate.queryForObject("SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        return id == null ? -1L : id;
    }

    /** 指派工单并返回完整响应（便于直接断言 code） */
    private JsonNode assignTicket(long id, Long assigneeId, long operatorId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assigneeId", assigneeId);
        body.put("operatorId", operatorId);

        MvcResult result = mockMvc.perform(post("/api/tickets/{id}/assign", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    /** 解决工单并返回完整响应 */
    private JsonNode resolveTicket(long id, long operatorId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operatorId", operatorId);
        body.put("remark", "问题已处理完毕");

        MvcResult result = mockMvc.perform(post("/api/tickets/{id}/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    /** 关闭工单并返回完整响应 */
    private JsonNode closeTicket(long id, long operatorId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operatorId", operatorId);
        body.put("remark", "问题已处理完毕，确认关闭");

        MvcResult result = mockMvc.perform(post("/api/tickets/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    /** 取操作日志的 data 数组（按 created_at 升序） */
    private JsonNode logsOf(long id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tickets/{id}/logs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).get("data");
    }

    /** 直接查库读取状态，用于验证"被拒绝后状态未被修改" */
    private String getStatusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, id);
    }

    private Map<String, Object> baseCreateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "基础标题");
        body.put("description", "描述");
        body.put("category", "网络故障");
        body.put("priority", "MEDIUM");
        body.put("tags", "tag1,tag2");
        body.put("creatorId", TEST_CREATOR_ID);
        return body;
    }

    private String toJson(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int countTicketRow(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

}
