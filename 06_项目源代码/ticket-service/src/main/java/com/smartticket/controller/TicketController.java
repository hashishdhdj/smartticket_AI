 package com.smartticket.controller;

import com.smartticket.common.Result;
import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.dto.TicketCloseDTO;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.dto.TicketResolveDTO;
import com.smartticket.dto.TicketUpdateDTO;
import com.smartticket.service.TicketService;
import com.smartticket.vo.PageVO;
import com.smartticket.vo.TicketLogVO;
import com.smartticket.vo.TicketVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单接口。
 *
 * <p>本层只做三件事：接收参数、调用 Service、用 {@link Result} 包装结果。
 * 不写任何业务判断（业务规则在 Service 与状态机里）。</p>
 *
 * <p>类上的 {@code @Validated} 用于开启方法参数校验，
 * 使 {@code @Min/@Max} 生效；校验失败由 GlobalExceptionHandler 统一转成 40000。</p>
 */
@Validated
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 1. 创建工单 */
    @PostMapping
    public Result<TicketVO> create(@Valid @RequestBody TicketCreateDTO dto) {
        return Result.success(ticketService.create(dto));
    }

    /** 2. 查询工单详情 */
    @GetMapping("/{id}")
    public Result<TicketVO> getById(@PathVariable Long id) {
        return Result.success(ticketService.getById(id));
    }

    /** 3. 分页查询工单（可选按状态筛选） */
    @GetMapping
    public Result<PageVO<TicketVO>> page(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为 1") long current,

            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数最小为 1")
            @Max(value = 100, message = "每页条数最大为 100") long size,

            @RequestParam(required = false) String status) {
        return Result.success(ticketService.page(current, size, status));
    }

    /** 4. 更新工单（仅 title/description/category/priority/tags） */
    @PutMapping("/{id}")
    public Result<TicketVO> update(@PathVariable Long id, @Valid @RequestBody TicketUpdateDTO dto) {
        return Result.success(ticketService.update(id, dto));
    }

    /** 5. 查询工单操作日志 */
    @GetMapping("/{id}/logs")
    public Result<List<TicketLogVO>> listLogs(@PathVariable Long id) {
        return Result.success(ticketService.listLogs(id));
    }

    /** 6. 指派工单（待分配 → 处理中；其它未关闭状态为转派） */
    @PostMapping("/{id}/assign")
    public Result<TicketVO> assign(@PathVariable Long id, @Valid @RequestBody TicketAssignDTO dto) {
        return Result.success(ticketService.assign(id, dto));
    }

    /** 7. 关闭工单（需通过状态机校验） */
    @PostMapping("/{id}/close")
    public Result<TicketVO> close(@PathVariable Long id, @Valid @RequestBody TicketCloseDTO dto) {
        return Result.success(ticketService.close(id, dto));
    }

    /** 8. 解决工单（需通过状态机校验：处理中 / 待反馈 → 已解决） */
    @PostMapping("/{id}/resolve")
    public Result<TicketVO> resolve(@PathVariable Long id, @Valid @RequestBody TicketResolveDTO dto) {
        return Result.success(ticketService.resolve(id, dto));
    }

}
