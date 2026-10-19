package com.smartticket.support;

import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;
import com.smartticket.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仅供测试使用的 Controller，<b>不属于生产代码</b>（放在 src/test/java 下，不会被打进 jar）。
 *
 * <p>存在的理由：第4天要验收"任意接口返回统一 JSON / 参数错误返回明确错误码"，
 * 但阶段1第4天禁止实现业务接口（工单接口在第6天）。因此在测试目录里造几个可控的异常入口，
 * 用 MockMvc 直接验证统一返回格式与各类异常的处理结果。</p>
 */
@RestController
@RequestMapping("/test")
public class TestApiController {

    /** 请求体：用于验证 @Valid 参数校验链路 */
    public record CreateRequest(@NotBlank(message = "标题不能为空") String title) {
    }

    /** 场景1：正常成功，带数据 */
    @GetMapping("/success")
    public Result<Map<String, Object>> success() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketNo", "ST-20260916-0001");
        data.put("status", "PENDING_ASSIGN");
        return Result.success(data);
    }

    /** 场景2：业务异常 */
    @GetMapping("/business-error")
    public Result<Void> businessError() {
        throw new BusinessException(ErrorCode.BUSINESS_ERROR, "工单不存在");
    }

    /** 场景3：参数校验失败（@Valid 触发） */
    @PostMapping("/validate")
    public Result<Void> validate(@Valid @RequestBody CreateRequest request) {
        return Result.success();
    }

    /** 场景4：请求体不是合法 JSON */
    @PostMapping("/parse-error")
    public Result<Void> parseError(@RequestBody CreateRequest request) {
        return Result.success();
    }

    /** 场景5：未预料的系统异常（文案里故意塞入内部细节，用于验证不会泄露给调用方） */
    @GetMapping("/system-error")
    public Result<Void> systemError() {
        throw new IllegalStateException("模拟系统异常：jdbc:mysql://127.0.0.1:3306/smartticket 连接失败");
    }

}
