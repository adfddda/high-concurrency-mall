package com.lxt.mall.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>插件顺序有讲究（MP 官方建议）：
 * 分页 → 乐观锁 → 防全表更新删除。
 * 顺序错了会出现"分页 SQL 里没拼上乐观锁条件"之类的隐蔽问题。
 */
@Configuration
@MapperScan("com.lxt.mall.domain.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限：不设的话 pageSize=1000000 会让一个请求把 DB 打挂。
        // 这是最容易被忽略、也最容易被利用的接口层风险。
        pagination.setMaxLimit(500L);
        // 页码超过总页数时不返回第一页，避免"翻到最后一页还在无限翻"的爬虫行为
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 拦截没有 WHERE 条件的 UPDATE / DELETE。
        // 一次手抖的 updateById(null) 或 wrapper 条件写漏，就是全表数据被改。
        // 有了它，这类操作在开发阶段就直接报错，不会流到生产。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
