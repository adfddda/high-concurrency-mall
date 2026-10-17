package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.CartItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 加购（幂等累加）。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 一条语句完成"有就加数量、没有就插入"：
     * 先 SELECT 再 INSERT/UPDATE 的做法在并发下会撞唯一键，
     * 而这里由数据库的原子性保证正确，应用层连重试都不用写。
     *
     * <p>{@code LEAST(..., 99)} 把单个 SKU 的数量封顶在 99：
     * 这是业务规则（防囤货/防误操作），放在 SQL 里比放在应用层更可靠——
     * 任何入口（包括将来的运营工具）都绕不过去。
     */
    @Insert("""
            INSERT INTO t_cart_item (id, user_id, sku_id, quantity, checked)
            VALUES (#{id}, #{userId}, #{skuId}, #{quantity}, 1)
            ON DUPLICATE KEY UPDATE quantity = LEAST(quantity + #{quantity}, 99)
            """)
    int upsert(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("skuId") Long skuId,
               @Param("quantity") int quantity);
}
