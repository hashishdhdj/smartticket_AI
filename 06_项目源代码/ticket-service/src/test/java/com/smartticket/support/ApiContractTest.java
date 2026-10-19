package com.smartticket.support;

import com.smartticket.mapper.TicketLogMapper;
import com.smartticket.mapper.TicketMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第4天验收测试：统一返回格式与全局异常处理的对外契约。
 *
 * <p>用 {@code @WebMvcTest} 只加载 Web 层：不启动数据库、不依赖 Docker，秒级可跑；
 * 每个用例都用 {@code print()} 打印真实响应报文，便于人工核对契约。</p>
 *
 * <p>两个 Mapper 必须用 {@code @MockBean} 顶掉：启动类上的
 * {@code @MapperScan} 会把 Mapper 注册进切片上下文，而 Web 切片不含 MyBatis 自动配置
 * （没有 SqlSessionFactory），不替换就会启动失败。Web 层契约测试本就不需要真实持久层。</p>
 */
@WebMvcTest(controllers = TestApiController.class)
class ApiContractTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403, 409, 429})
    void explicitApiFailuresHaveTheSameEnvelope(int http) throws Exception {
        mockMvc.perform(get("/test/api-error/{status}", http))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(http * 100))
                .andExpect(jsonPath("$.message").value("受控错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "TICKET_NOT_FOUND,404,10003", "INVALID_STATUS_TRANSITION,409,10001",
        "UNKNOWN_STATUS_CODE,400,10002", "AI_CALL_FAILED,503,10007"
    })
    void typedBusinessErrorsUseExplicitHttpStatus(String code, int http, int business) throws Exception {
        mockMvc.perform(get("/test/typed-business/{code}", code))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(business))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unsupportedMethodPreserves405AndAllowHeader() throws Exception {
        mockMvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Allow"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unsupportedMediaTypePreserves415() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求内容类型不支持"));
    }

    @Test
    void invalidPathParameterReturns400WithoutReflectionDetails() throws Exception {
        mockMvc.perform(get("/test/api-error/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    void missingRoutePreserves404() throws Exception {
        mockMvc.perform(get("/test/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Autowired
    private MockMvc mockMvc;

    /** 仅为满足切片上下文启动，本测试不使用其行为 */
    @MockBean
    private TicketMapper ticketMapper;

    /** 同上 */
    @MockBean
    private TicketLogMapper ticketLogMapper;

    @Test
    @DisplayName("成功响应：code=0、message=成功、data 带业务数据")
    void successResponseHasCodeMessageAndData() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.ticketNo").value("ST-20260916-0001"))
                .andExpect(jsonPath("$.data.status").value("PENDING_ASSIGN"));
    }

    @Test
    @DisplayName("业务异常：HTTP 400，业务码 10000，文案来自异常")
    void businessExceptionReturnsHttp400WithBusinessCode() throws Exception {
        mockMvc.perform(get("/test/business-error"))
                .andDo(print())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("工单不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("参数校验失败：错误码 40000，提示里带字段名")
    void validationFailureReturnsParamInvalidCode() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andDo(print())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message", containsString("title")))
                .andExpect(jsonPath("$.message", containsString("标题不能为空")));
    }

    @Test
    @DisplayName("请求体格式错误：错误码 40001")
    void malformedJsonReturnsUnreadableCode() throws Exception {
        mockMvc.perform(post("/test/parse-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this-is-not-json"))
                .andDo(print())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("系统异常：错误码 50000 且不泄露内部细节")
    void systemExceptionHidesInternalDetail() throws Exception {
        mockMvc.perform(get("/test/system-error"))
                .andDo(print())
                .andExpect(status().is(500))
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.message", not(containsString("模拟系统异常"))))
                .andExpect(jsonPath("$.message", not(containsString("jdbc:mysql"))))
                .andExpect(jsonPath("$.message", not(containsString("IllegalStateException"))));
    }

}
