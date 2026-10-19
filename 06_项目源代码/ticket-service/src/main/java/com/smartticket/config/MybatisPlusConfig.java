package com.smartticket.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>分页功能必须注册 {@link PaginationInnerInterceptor}：
 * 不注册时 {@code selectPage} 不会生成 {@code LIMIT}，会把全表查出来当"第一页"，
 * 且 total 也不准确。第3天特意引入的 {@code mybatis-plus-jsqlparser} 正是该插件的依赖
 * （3.5.9 起从主包拆出）。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}
