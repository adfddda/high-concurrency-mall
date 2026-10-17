package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.GroupBuyMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupBuyMemberMapper extends BaseMapper<GroupBuyMember> {

    /** 成员支付成功：待支付 → 已支付。重复回调返回 0，不会重复计数。 */
    @Update("""
            UPDATE t_group_buy_member
               SET status = 1
             WHERE id = #{id}
               AND status = 0
            """)
    int markPaid(@Param("id") Long id);

    /** 拼团失败退款后标记。 */
    @Update("""
            UPDATE t_group_buy_member
               SET status = 2
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int markRefunded(@Param("orderNo") String orderNo);
}
