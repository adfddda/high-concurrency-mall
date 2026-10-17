package com.lxt.mall.ratelimit;

import com.lxt.mall.common.enums.RateLimitDimension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式限流。
 *
 * <p>用法：
 * <pre>
 *   &#64;RateLimit(rule = "seckill", dimensions = {USER, IP})
 *   public Result&lt;SeckillResponse&gt; seckill(...) { ... }
 * </pre>
 *
 * <p>阈值本身<b>不写在注解里</b>，而是通过 {@code rule} 引用
 * {@code mall.rate-limit.rules.*} 配置。理由：
 * <ul>
 *   <li>阈值是要随大促、压测、故障复盘频繁调整的东西，改配置能热更新（配合配置中心），
 *       改注解必须重新发版；</li>
 *   <li>同一个规则可能被多个接口共用，写在配置里只维护一份；</li>
 *   <li>代码 review 时不会被一堆魔数淹没。</li>
 * </ul>
 */
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 引用 {@code mall.rate-limit.rules.<rule>} 的键名。 */
    String rule();

    /** 需要叠加的限流维度。任一个维度被限流，请求即被拒绝。 */
    RateLimitDimension[] dimensions() default {RateLimitDimension.USER};

    /** 被限流时的提示文案，留空则用默认文案。 */
    String message() default "";
}
