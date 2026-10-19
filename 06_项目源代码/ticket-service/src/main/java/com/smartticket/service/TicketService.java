package com.smartticket.service;

import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.dto.TicketCloseDTO;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.dto.TicketResolveDTO;
import com.smartticket.dto.TicketUpdateDTO;
import com.smartticket.vo.PageVO;
import com.smartticket.vo.TicketLogVO;
import com.smartticket.vo.TicketVO;

import java.util.List;

/**
 * 工单业务接口。
 *
 * <p>所有业务规则（状态机校验、编号生成、事务、写操作日志）都在实现类中，
 * Controller 只负责参数接收与结果包装。</p>
 */
public interface TicketService {

    /** 创建工单，同时写入一条 CREATE 操作日志；初始状态固定为待分配 */
    TicketVO create(TicketCreateDTO dto);

    /** 按 id 查询工单详情，不存在时抛业务异常（10003） */
    TicketVO getById(Long id);

    /**
     * 分页查询工单。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数，上限由 Controller 校验
     * @param status  可选状态筛选（英文状态值，非法值抛 10002）
     */
    PageVO<TicketVO> page(long current, long size, String status);

    /** 更新工单（仅 title/description/category/priority/tags），不存在时抛业务异常（10003） */
    TicketVO update(Long id, TicketUpdateDTO dto);

    /** 查询某工单的操作日志，按时间升序；工单不存在时抛业务异常（10003） */
    List<TicketLogVO> listLogs(Long id);

    /**
     * 指派工单：写入处理人；若当前为「待分配」则同时流转到「处理中」，
     * 其它未关闭状态视为转派（只换人，状态不变），已关闭状态拒绝（10005）。
     */
    TicketVO assign(Long id, TicketAssignDTO dto);

    /** 关闭工单：经状态机校验后流转到「已关闭」，非法流转抛业务异常（10001） */
    TicketVO close(Long id, TicketCloseDTO dto);

    /** 解决工单：经状态机校验后流转到「已解决」；处理中与待反馈可流转，其余抛 10001 */
    TicketVO resolve(Long id, TicketResolveDTO dto);

}
