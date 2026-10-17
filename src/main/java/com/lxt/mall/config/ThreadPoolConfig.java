package com.lxt.mall.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。
 *
 * <p>统一原则：<b>绝不使用 {@code Executors.newXxx()} 的默认构造</b>。
 * 无界队列 + 无界线程数的默认实现，在流量突增时只会把问题从"拒绝服务"
 * 变成"OOM"，而且是在几分钟后以更难排查的方式爆发。
 * 每个池都显式指定：核心数、最大数、队列容量、拒绝策略、线程命名。
 *
 * <p>线程命名我特意加了业务前缀：出问题时 {@code jstack} 一眼能看出
 * 是哪个池打满了，不用去数 {@code pool-7-thread-13} 这种毫无信息量的名字。
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    /**
     * 缓存异步重建线程池。
     *
     * <p>用于「逻辑过期」方案：发现缓存逻辑过期时，不阻塞请求线程，
     * 而是丢到这个池里异步去查 DB 重建缓存，当前请求直接返回旧数据。
     *
     * <p>拒绝策略选 {@link ThreadPoolExecutor.CallerRunsPolicy}：
     * 队列满说明重建任务已经积压，此时让调用线程自己跑，
     * 相当于给上游一个自然的背压（请求变慢而不是被丢弃），
     * 也不会出现"缓存永远重建不出来"的雪崩式恶化。
     */
    @Bean(name = "cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor(MallProperties properties) {
        MallProperties.ProductCache pc = properties.getCache().getProduct();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pc.getRebuildPoolSize());
        executor.setMaxPoolSize(pc.getRebuildPoolSize() * 2);
        executor.setQueueCapacity(pc.getRebuildQueueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mall-cache-rebuild-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 业务定时任务的调度器（`@Scheduled` 用的就是它）。
     *
     * <h3>为什么必须显式定义这个 Bean（这是一个真实踩到的坑）</h3>
     * Spring Boot 的 {@code TaskSchedulingAutoConfiguration} 只会在
     * <b>容器里不存在 {@code TaskScheduler} Bean</b> 时才自动装配一个。
     * 一旦我们自己定义了任何一个 {@code ThreadPoolTaskScheduler}，
     * 自动装配就被抑制了，而 {@code @EnableScheduling} 会把那个 Bean
     * <b>当成所有定时任务的调度器</b>。
     *
     * <p>结果就是：本项目原本只定义了一个「延迟双删专用」的<b>单线程</b>调度器，
     * 于是对账、预热、超时兜底、投递补偿这 4 个 {@code @Scheduled} 任务
     * 全部挤在同一个线程上排队执行，而且和延迟双删互相阻塞。
     * 日志里能看到 {@code mall-cache-double-delete-1} 这个线程正在跑
     * 「订单超时兜底扫描」——名字和职责完全对不上，就是这个问题。
     *
     * <p>危害在于<b>一个任务卡住会让所有任务停摆</b>：比如对账任务扫到一张大表
     * 卡了 3 分钟，期间的超时关单兜底也跟着停了，订单会长时间停留在待支付状态。
     *
     * <p>修正是定义两个职责清晰的调度器：
     * <ul>
     *   <li>{@code taskScheduler}（本方法，4 线程 + @Primary）—— 所有 {@code @Scheduled} 任务</li>
     *   <li>{@code cacheDoubleDeleteScheduler}（单线程）—— 只服务延迟双删</li>
     * </ul>
     * Bean 名字刻意取成 {@code taskScheduler}：Spring 的
     * {@code ScheduledAnnotationBeanPostProcessor} 会<b>优先按这个名字查找</b>，
     * 加上 {@code @Primary} 双保险，避免类型注入时的歧义。
     */
    @Bean(name = "taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler mallTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("mall-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        // 全局兜底异常处理：@Scheduled 方法抛出的异常如果没人接，
        // Spring 只会打一行日志，而且默认行为是【停止后续调度】——
        // 一个偶发异常就会让这个任务永远不再执行。这是最容易埋雷的地方。
        scheduler.setErrorHandler(t -> log.error("定时任务执行失败（已捕获，后续调度不受影响）", t));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 缓存延迟双删调度器。
     *
     * <p>池大小刻意设为 1：延迟删除任务非常轻（一条 DEL），
     * 单线程足够，还能保证同一时刻只有一个删除任务在跑，便于排查。
     *
     * <p>用 {@code @Qualifier("cacheDoubleDeleteScheduler")} 显式注入，
     * 不要让它成为默认调度器——见 {@link #mallTaskScheduler()} 的说明。
     */
    @Bean(name = "cacheDoubleDeleteScheduler")
    public ThreadPoolTaskScheduler cacheDoubleDeleteScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mall-cache-double-delete-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.error("延迟双删任务执行失败", t));
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 秒杀补偿线程池。
     *
     * <p>投递失败时回补 Redis 库存。用 CallerRunsPolicy 同上：
     * 补偿是必须发生的事，宁可拖慢当前请求也不能默默丢掉。
     */
    @Bean(name = "seckillCompensateExecutor")
    public ThreadPoolTaskExecutor seckillCompensateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(4096);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mall-seckill-compensate-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
