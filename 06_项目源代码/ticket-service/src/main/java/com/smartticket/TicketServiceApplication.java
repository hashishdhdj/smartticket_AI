package com.smartticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能工单服务启动类。
 *
 * <p>阶段1：已具备数据访问能力（MyBatis-Plus + MySQL），暂未实现业务接口。</p>
 */
@SpringBootApplication
@MapperScan("com.smartticket.mapper")
public class TicketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketServiceApplication.class, args);
    }

}
