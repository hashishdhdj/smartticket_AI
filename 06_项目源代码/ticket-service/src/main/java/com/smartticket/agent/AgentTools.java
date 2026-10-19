package com.smartticket.agent;

import com.fasterxml.jackson.databind.*;
import com.smartticket.phase3.*;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.service.TicketService;

import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class AgentTools {
    // Anchored: never reinterpret topic-bearing or multi-ticket requests.
    private static final Pattern TICKET_ONLY_QUERY = Pattern.compile(
            "^(?:请|查询|查看|检索|搜索|关于|针对|给|为|一下|\\s)*(?:工单\\s*#?\\s*([0-9]{1,19})\\s*号?|#?\\s*([0-9]{1,19})\\s*号工单)"
                    + "(?:\\s|的|请|查询|查看|检索|搜索|一下|相关|处理|解决|建议|方案|方法|资料|知识|帮助|给出|提供|怎么办|[，,:：。？?])*$");
    private final TicketService tickets;
    private final KnowledgeSearchService knowledge;
    private final AssignmentActions actions;
    private final ObjectMapper json;

    public AgentTools(
            TicketService t, KnowledgeSearchService k, AssignmentActions a, ObjectMapper j) {
        tickets = t;
        knowledge = k;
        actions = a;
        json = j;
    }

    public List<ToolCallback> definitions() {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(
                def(
                        "getTicketDetail",
                        "查询有权访问的工单详情；必须先得到真实结果才能报告状态",
                        schema("ticketId", "integer")));
        tools.add(def("searchKnowledge", "用问题关键词检索知识；仅含工单编号的通用查询会经权限校验后用标题补全，返回实际查询；没有依据转人工", schema("query", "string")));
        tools.add(def("getTicketLogs", "查询有权访问的工单最近操作记录", schema("ticketId", "integer")));
        if (ActorContext.require().admin())
            tools.add(
                    def(
                            "prepareAssignTicket",
                            "生成待人工确认的指派提案，不会执行指派；禁止声称已执行",
                            "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"integer\",\"minimum\":1},\"assigneeId\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"ticketId\",\"assigneeId\"],\"additionalProperties\":false}"));
        return tools;
    }

    private String schema(String field, String type) {
        return "{\"type\":\"object\",\"properties\":{\""
                + field
                + "\":{\"type\":\""
                + type
                + "\"}},\"required\":[\""
                + field
                + "\"],\"additionalProperties\":false}";
    }

    private ToolCallback def(String name, String desc, String schema) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(desc)
                        .inputSchema(schema)
                        .build();
            }

            public String call(String input) {
                throw new IllegalStateException(
                        "Only the bounded agent dispatcher can execute tools");
            }
        };
    }

    public Object execute(String name, String arguments, String runId) throws Exception {
        ActorContext.require();
        if (arguments == null || arguments.length() > 4000)
            throw new ApiFailure(400, 40000, "工具参数过长");
        JsonNode args = json.readTree(arguments);
        if (args == null || !args.isObject()) throw new ApiFailure(400, 40000, "工具参数必须是对象");
        Set<String> required =
                switch (name) {
                    case "getTicketDetail", "getTicketLogs" -> Set.of("ticketId");
                    case "searchKnowledge" -> Set.of("query");
                    case "prepareAssignTicket" -> Set.of("ticketId", "assigneeId");
                    default -> throw new ApiFailure(400, 40000, "未注册工具");
                };
        Set<String> actual = new HashSet<>();
        args.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw new ApiFailure(400, 40000, "工具参数字段不符合契约");
        return switch (name) {
            case "getTicketDetail" -> {
                var t = tickets.getById(id(args, "ticketId"));
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("id", t.getId());
                detail.put("version", t.getVersion());
                detail.put("title", t.getTitle());
                detail.put("status", t.getStatus());
                detail.put("priority", t.getPriority());
                detail.put("assigneeId", t.getAssigneeId());
                detail.put(
                        "description",
                        t.getDescription() == null
                                ? null
                                : t.getDescription()
                                        .substring(0, Math.min(2000, t.getDescription().length())));
                yield detail;
            }
            case "getTicketLogs" -> {
                var logs = tickets.listLogs(id(args, "ticketId"));
                yield logs.stream().skip(Math.max(0, logs.size() - 20)).toList();
            }
            case "prepareAssignTicket" ->
                    actions.prepare(id(args, "ticketId"), id(args, "assigneeId"), runId);
            case "searchKnowledge" -> {
                if (!args.get("query").isTextual()) throw new ApiFailure(400, 40000, "query必须是文本");
                String q = args.get("query").textValue();
                if (q.isBlank() || q.length() > 1000) throw new ApiFailure(400, 40000, "检索文本长度无效");
                String effective = knowledgeQuery(q);
                var result = knowledge.search(effective);
                yield Map.of(
                        "requestedQuery", q,
                        "effectiveQuery", effective,
                        "queryOrigin", effective.equals(q) ? "MODEL_QUERY" : "AUTHORIZED_TICKET_TITLE",
                        "sources",
                        result.hits().stream()
                                .limit(3)
                                .map(
                                        h ->
                                                Map.of(
                                                        "sourceId",
                                                        h.chunk().sourceFile()
                                                                + "#"
                                                                + h.chunk().chunkIndex(),
                                                        "title",
                                                        h.chunk().sectionTitle(),
                                                        "excerpt",
                                                        h.chunk()
                                                                .content()
                                                                .substring(
                                                                        0,
                                                                        Math.min(
                                                                                1500,
                                                                                h.chunk()
                                                                                        .content()
                                                                                        .length()))))
                                .toList(),
                        "degraded",
                        result.degradedSources());
            }
            default -> throw new ApiFailure(400, 40000, "未注册工具");
        };
    }

    private String knowledgeQuery(String requested) {
        var match = TICKET_ONLY_QUERY.matcher(requested.strip());
        if (!match.matches()) return requested;
        long ticketId;
        try {
            ticketId = Long.parseLong(match.group(1) != null ? match.group(1) : match.group(2));
        } catch (NumberFormatException invalid) {
            throw new ApiFailure(400, 40000, "工单编号无效");
        }
        if (ticketId <= 0) throw new ApiFailure(400, 40000, "工单编号必须为正整数");
        var ticket = tickets.getById(ticketId); // Same object-level policy as getTicketDetail.
        String title = ticket.getTitle();
        if (title == null || title.isBlank() || title.length() > 1000)
            throw new ApiFailure(400, 40000, "请提供具体问题关键词");
        return title.strip();
    }

    private long id(JsonNode args, String key) {
        var n = args.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() <= 0)
            throw new ApiFailure(400, 40000, "工具编号必须为正整数");
        return n.longValue();
    }
}
