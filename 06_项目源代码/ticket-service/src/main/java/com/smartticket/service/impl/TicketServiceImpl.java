package com.smartticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.dto.TicketCloseDTO;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.dto.TicketResolveDTO;
import com.smartticket.dto.TicketUpdateDTO;
import com.smartticket.entity.Ticket;
import com.smartticket.entity.TicketLog;
import com.smartticket.enums.ErrorCode;
import com.smartticket.enums.TicketPriority;
import com.smartticket.enums.TicketStatus;
import com.smartticket.exception.BusinessException;
import com.smartticket.mapper.TicketLogMapper;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.service.TicketService;
import com.smartticket.vo.PageVO;
import com.smartticket.phase3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.smartticket.vo.TicketLogVO;
import com.smartticket.vo.TicketVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单业务实现。
 *
 * <p>职责：状态与默认值处理、工单编号生成、事务边界、操作日志写入。
 * 数据访问全部通过 Mapper，不直接拼 SQL。</p>
 */
@Slf4j
@Service
public class TicketServiceImpl implements TicketService {

    /** 工单编号格式：ST-yyyyMMdd-HHmmss-4位十六进制随机 */
    private static final DateTimeFormatter TICKET_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 编号撞唯一键时的最大尝试次数 */
    private static final int TICKET_NO_MAX_ATTEMPTS = 3;

    /** 日志动作：创建 */
    private static final String ACTION_CREATE = "CREATE";

    /** 日志动作：指派 */
    private static final String ACTION_ASSIGN = "ASSIGN";

    /** 日志动作：关闭 */
    private static final String ACTION_CLOSE = "CLOSE";

    /** 日志动作：解决 */
    private static final String ACTION_RESOLVE = "RESOLVE";

    /** 工单详情缓存 key 前缀，完整 key 形如 ticket:detail:123 */
    private static final String CACHE_KEY_DETAIL_PREFIX = "ticket:detail:";

    /**
     * 详情缓存过期时间（2026-09-21 决策：10 分钟）。
     * <p>TTL 只作兜底——保证一致性的是写操作主动删缓存；
     * 它的作用是"万一某个写路径漏删，脏数据也不会永久驻留"。</p>
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    @Autowired(required=false) private Phase3Repository phase3;
    @Autowired(required=false) private Phase3Properties phase3Config;
    @Autowired(required=false) private AccessPolicy access;
    @Autowired(required=false) private Phase3Metrics metrics;

    private final TicketMapper ticketMapper;

    private final TicketLogMapper ticketLogMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    public TicketServiceImpl(TicketMapper ticketMapper, TicketLogMapper ticketLogMapper,
                             StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.ticketLogMapper = ticketLogMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO create(TicketCreateDTO dto) {
        Ticket ticket = new Ticket();
        ticket.setTitle(dto.getTitle());
        ticket.setDescription(dto.getDescription());
        ticket.setCategory(dto.getCategory());
        // 未传优先级时取数据库默认值 MEDIUM，显式写入以保证返回的出参与库内一致
        ticket.setPriority(dto.getPriority() == null ? TicketPriority.MEDIUM.name() : dto.getPriority());
        ticket.setTags(dto.getTags());
        ticket.setCreatorId(ActorContext.current()==null?dto.getCreatorId():ActorContext.require().id());
        ticket.setVersion(0L);
        // 状态固定为待分配（2026-09-18 决策：创建接口不接受状态入参）
        ticket.setStatus(TicketStatus.PENDING_ASSIGN.name());

        insertTicketWithUniqueNo(ticket);
        log.info("工单创建成功 | id={} | ticketNo={} | creatorId={}",
                ticket.getId(), ticket.getTicketNo(), ticket.getCreatorId());

        writeLog(ticket.getId(), ticket.getCreatorId(), ACTION_CREATE,
                null, ticket.getStatus(), "创建工单");

        if(phase3!=null && phase3Config.isRecordTasks())phase3.createTask(ticket,0,null);

        // 回读一次再返回：MyBatis-Plus 的 insert 只回填主键，
        // 不会回填由数据库默认值生成的 created_at / updated_at，
        // 不回读就会返回 createdAt=null（且因 non_null 配置被整个省略）。
        return TicketVO.from(requireTicket(ticket.getId()));
    }

    @Override
    public TicketVO getById(Long id) {
        Ticket authorized = access==null?null:requireTicket(id);
        if(authorized!=null)access.read(authorized);
        String cacheKey = detailCacheKey(id);

        // 1. 先查缓存（Cache-Aside 的读路径）。
        //    缓存是加速器而不是必需品：Redis 不可用时降级为直接查库，不让读接口整体失败。
        String cached = readCacheQuietly(cacheKey, id);
        if (cached != null) {
            try {
                TicketVO cachedVO = objectMapper.readValue(cached, TicketVO.class);
                log.debug("工单详情命中缓存 | id={}", id);
                if(authorized==null || java.util.Objects.equals(cachedVO.getVersion(),authorized.getVersion())) {
                    if(metrics!=null)metrics.count("cache","hit");
                    return cachedVO;
                }
                evictDetailCache(id);
            } catch (JsonProcessingException e) {
                // 缓存内容不可信（被人为改动、VO 结构升级等）不应让查询失败：
                // 清掉坏数据后继续走下面的回源逻辑
                log.warn("工单详情缓存不可解析，清除后回源 | id={}", id);
                evictDetailCache(id);
            }
        }

        // 2. 未命中：回源数据库
        log.debug("工单详情回源数据库 | id={}", id);
        TicketVO ticketVO = TicketVO.from(authorized==null?requireTicket(id):authorized);
        if(metrics!=null)metrics.count("cache","miss");

        // 3. 回填缓存。一致性靠写操作主动删除，TTL 只作兜底；回填失败不影响本次返回
        writeCacheQuietly(cacheKey, ticketVO, id);

        return ticketVO;
    }

    @Override
    public PageVO<TicketVO> page(long current, long size, String status) {
        String statusFilter = null;
        if (StringUtils.hasText(status)) {
            // 复用状态机的取值校验：非法状态值直接抛 10002，而不是静默查空
            statusFilter = TicketStatus.fromCode(status).name();
        }

        LambdaQueryWrapper<Ticket> wrapper = Wrappers.lambdaQuery(Ticket.class)
                .eq(statusFilter != null, Ticket::getStatus, statusFilter)
                .orderByDesc(Ticket::getCreatedAt)
                .orderByDesc(Ticket::getId);

        var actor=ActorContext.current();
        if(actor!=null && !actor.admin()) {
            if("HANDLER".equals(actor.role()))wrapper.eq(Ticket::getAssigneeId,actor.id());
            else wrapper.eq(Ticket::getCreatorId,actor.id());
        } else if(access!=null && phase3Config.isSecurityEnabled())ActorContext.require();
        Page<Ticket> pageResult = ticketMapper.selectPage(new Page<>(current, size), wrapper);
        List<TicketVO> records = pageResult.getRecords().stream().map(TicketVO::from).toList();

        return PageVO.of(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO update(Long id, TicketUpdateDTO dto) {
        Ticket ticket=requireTicket(id);
        if(access!=null)access.write(ticket);
        if (dto.getExpectedVersion() != null && !dto.getExpectedVersion().equals(ticket.getVersion()))
            throw com.smartticket.phase3.ApiFailure.conflict();

        // 必须用 UpdateWrapper 显式 set：updateById 会跳过 null 字段，
        // 而本接口的语义是"全量替换"，需要支持把 description/category/tags 置空。
        LambdaUpdateWrapper<Ticket> updateWrapper = versioned(ticket)
                .set(Ticket::getTitle, dto.getTitle())
                .set(Ticket::getDescription, dto.getDescription())
                .set(Ticket::getCategory, dto.getCategory())
                .set(Ticket::getPriority, dto.getPriority())
                .set(Ticket::getTags, dto.getTags());
        requireUpdated(ticketMapper.update(null, updateWrapper));
        writeLog(id,operator(dto.getOperatorId()),"UPDATE",ticket.getStatus(),ticket.getStatus(),"更新工单字段");

        evictDetailCache(id);

        // 回读一次：拿到数据库 ON UPDATE 维护的 updated_at，保证出参与库内一致
        return TicketVO.from(requireTicket(id));
    }

    @Override
    public List<TicketLogVO> listLogs(Long id) {
        Ticket ticket=requireTicket(id);
        if(access!=null)access.read(ticket);

        List<TicketLog> logs = ticketLogMapper.selectList(Wrappers.lambdaQuery(TicketLog.class)
                .eq(TicketLog::getTicketId, id)
                .orderByAsc(TicketLog::getCreatedAt)
                .orderByAsc(TicketLog::getId));

        return logs.stream().map(TicketLogVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO assign(Long id, TicketAssignDTO dto) {
        Ticket ticket = requireTicket(id);
        if(access!=null)access.write(ticket);
        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());

        // 已关闭的工单不允许指派：经状态机流转 CLOSED → PROCESSING 是"重开"，
        // 不应由指派接口隐式触发（重开应作为独立操作，本阶段未实现）。
        if (currentStatus == TicketStatus.CLOSED) {
            throw new BusinessException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    "已关闭的工单不能指派，如需继续处理请先重开，id=" + id);
        }

        // 指派语义（2026-09-18 用户决策）：待分配 → 经状态机流转到处理中；
        // 其它未关闭状态视为转派，只换处理人、状态不变。
        if(access!=null){access.admin();if(phase3Config.isSecurityEnabled()&&!access.validHandler(dto.getAssigneeId()))throw new ApiFailure(400,40000,"处理人不存在或角色无效");}
        boolean transfer = currentStatus != TicketStatus.PENDING_ASSIGN;
        TicketStatus targetStatus = transfer ? currentStatus : TicketStatus.PROCESSING;
        if (!transfer) {
            // 状态机是流转的唯一裁判，禁止在此另写 if 判断合法性
            currentStatus.checkTransitionTo(targetStatus);
        }

        requireUpdated(ticketMapper.update(null, versioned(ticket)
                .set(Ticket::getAssigneeId, dto.getAssigneeId())
                .set(Ticket::getStatus, targetStatus.name())));

        writeLog(id, operator(dto.getOperatorId()), ACTION_ASSIGN,
                currentStatus.name(), targetStatus.name(),
                defaultRemark(dto.getRemark(), transfer ? "转派处理人" : "指派处理人"));

        evictDetailCache(id);

        return TicketVO.from(requireTicket(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO close(Long id, TicketCloseDTO dto) {
        Ticket ticket = requireTicket(id);
        if(access!=null)access.write(ticket);
        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());

        // 关闭即一次状态流转：按第5天矩阵，只有「待分配」「已解决」允许流转到「已关闭」，
        // 「处理中 → 已关闭」「已关闭 → 已关闭」都会被状态机以 10001 拒绝。
        currentStatus.checkTransitionTo(TicketStatus.CLOSED);

        requireUpdated(ticketMapper.update(null, versioned(ticket)
                .set(Ticket::getStatus, TicketStatus.CLOSED.name())));

        writeLog(id, operator(dto.getOperatorId()), ACTION_CLOSE,
                currentStatus.name(), TicketStatus.CLOSED.name(),
                defaultRemark(dto.getRemark(), "关闭工单"));

        evictDetailCache(id);

        return TicketVO.from(requireTicket(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO resolve(Long id, TicketResolveDTO dto) {
        Ticket ticket = requireTicket(id);
        if(access!=null)access.write(ticket);
        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());

        // 解决即一次状态流转：按第5天矩阵，只有「处理中」「待反馈」允许流转到「已解决」。
        // 「待分配 → 已解决」（跳步）与「已解决 → 已解决」（自身流转）都会被状态机拒绝。
        currentStatus.checkTransitionTo(TicketStatus.RESOLVED);

        requireUpdated(ticketMapper.update(null, versioned(ticket)
                .set(Ticket::getStatus, TicketStatus.RESOLVED.name())));

        writeLog(id, operator(dto.getOperatorId()), ACTION_RESOLVE,
                currentStatus.name(), TicketStatus.RESOLVED.name(),
                defaultRemark(dto.getRemark(), "处理完毕，已解决"));

        evictDetailCache(id);

        return TicketVO.from(requireTicket(id));
    }

    /** 查询工单，逻辑删除的行查不到（@TableLogic 自动追加 deleted = 0） */
    private Ticket requireTicket(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND, "工单不存在或已删除，id=" + id);
        }
        return ticket;
    }

    /**
     * 插入工单，编号撞唯一键（uk_ticket_no）时重新生成并重试。
     *
     * <p>编号含 4 位随机段，理论冲突概率低；重试上限 {@value #TICKET_NO_MAX_ATTEMPTS} 次，
     * 仍失败则抛业务异常，避免无限重试。</p>
     */
    private void insertTicketWithUniqueNo(Ticket ticket) {
        for (int attempt = 1; attempt <= TICKET_NO_MAX_ATTEMPTS; attempt++) {
            ticket.setTicketNo(generateTicketNo());
            try {
                ticketMapper.insert(ticket);
                return;
            } catch (DuplicateKeyException e) {
                log.warn("工单编号冲突，第 {} 次尝试 | ticketNo={}", attempt, ticket.getTicketNo());
            }
        }
        throw new BusinessException(ErrorCode.TICKET_NO_CONFLICT);
    }

    /** 生成工单编号：ST-yyyyMMdd-HHmmss-4位随机（长度 23，不超过列宽 32） */
    private String generateTicketNo() {
        return "ST-" + LocalDateTime.now().format(TICKET_NO_FORMATTER)
                + "-" + String.format("%04X", ThreadLocalRandom.current().nextInt(0x10000));
    }

    /** 备注为空时给出默认说明，保证操作日志可读 */
    private String defaultRemark(String remark, String fallback) {
        return StringUtils.hasText(remark) ? remark : fallback;
    }

    /** 写入操作日志 */
    private void writeLog(Long ticketId, Long operatorId, String action,
                          String fromStatus, String toStatus, String remark) {
        TicketLog ticketLog = new TicketLog();
        ticketLog.setTicketId(ticketId);
        ticketLog.setOperatorId(operatorId);
        ticketLog.setAction(action);
        ticketLog.setFromStatus(fromStatus);
        ticketLog.setToStatus(toStatus);
        ticketLog.setRemark(remark);
        ticketLogMapper.insert(ticketLog);
    }

    // ------------------------------------------------------------ 详情缓存（第9天）

    /** 详情缓存 key：ticket:detail:{id} */
    private String detailCacheKey(Long id) {
        return CACHE_KEY_DETAIL_PREFIX + id;
    }

    /**
     * 清除详情缓存。所有会修改工单的写操作（更新 / 关闭 / 指派）改库后都必须调用，
     * 否则接口会继续返回旧标题、旧状态、旧处理人，直到 TTL 到期才自愈。
     *
     * <p>Redis 不可用时不能让写操作跟着失败：库已经改成功了，此时抛异常会让调用方
     * 误以为操作失败。删不掉只影响一致性，TTL 到期后自愈。</p>
     */
    private LambdaUpdateWrapper<Ticket> versioned(Ticket ticket) {
        return Wrappers.lambdaUpdate(Ticket.class).eq(Ticket::getId,ticket.getId())
                .eq(Ticket::getVersion,ticket.getVersion()==null?0L:ticket.getVersion())
                .setSql("version = version + 1");
    }
    private void requireUpdated(int rows){if(rows!=1)throw ApiFailure.conflict();}
    private long operator(Long fallback){return ActorContext.current()==null?fallback:ActorContext.require().id();}
    private void evictDetailCache(Long id) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            if(phase3!=null)phase3.invalidate(id);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){deleteCacheNow(id);}
            });
        } else deleteCacheNow(id);
    }
    private void deleteCacheNow(Long id) {
        try {
            Boolean removed = stringRedisTemplate.delete(detailCacheKey(id));
            log.debug("清除工单详情缓存 | id={} | removed={}", id, removed);
        } catch (RuntimeException e) {
            log.warn("清除工单详情缓存失败，待 TTL 到期自愈 | id={} | cause={}", id, e.getMessage());
        }
    }

    /**
     * 读缓存。Redis 不可用时返回 null（等同于未命中），由调用方回源数据库。
     *
     * <p>刻意不让缓存故障向上传播：Redis 重启、网络抖动在真实环境都会发生，
     * 此时"查得慢一点"远好于"接口直接不可用"。</p>
     */
    private String readCacheQuietly(String cacheKey, Long id) {
        try {
            return stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("读取工单详情缓存失败，降级为直接查库 | id={} | cause={}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 回填缓存，Redis 写入失败只记日志——本次查询结果已经拿到，不应因为写缓存失败而失败。
     *
     * <p>{@code writeJson} 刻意留在 try 之外：序列化失败属于代码/结构问题（VO 改了但这里没跟上），
     * 应当暴露而不是被当作缓存故障吞掉；Redis 写入失败才是可降级的环境问题。</p>
     */
    private void writeCacheQuietly(String cacheKey, TicketVO ticketVO, Long id) {
        String json = writeJson(ticketVO, id);
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("回填工单详情缓存失败，不影响本次返回 | id={} | cause={}", id, e.getMessage());
        }
    }

    /** VO → JSON。序列化失败说明对象结构与预期不符，属系统级问题，直接暴露而不静默跳过 */
    private String writeJson(TicketVO ticketVO, Long id) {
        try {
            return objectMapper.writeValueAsString(ticketVO);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工单详情序列化失败，id=" + id);
        }
    }

}
