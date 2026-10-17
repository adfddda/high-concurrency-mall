package com.lxt.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 高并发商城交易系统启动类。
 *
 * <ul>
 *   <li>{@code @EnableScheduling} —— 对账、投递补偿等定时任务</li>
 *   <li>{@code @EnableAsync} —— 缓存异步重建（逻辑过期方案的关键）</li>
 * </ul>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
