package com.smartticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartticket.entity.Ticket;

/**
 * 工单主表数据访问接口。
 *
 * <p>继承 BaseMapper 后即拥有 insert、deleteById、updateById、selectById、selectList、selectCount
 * 等方法，<b>不需要写实现类</b>——MyBatis 会用 JDK 动态代理在运行时生成实现。</p>
 *
 * <p>本接口通过启动类上的 {@code @MapperScan("com.smartticket.mapper")} 被扫描注册为 Bean。</p>
 */
public interface TicketMapper extends BaseMapper<Ticket> {

}
