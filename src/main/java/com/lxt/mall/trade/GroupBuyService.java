package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.GroupBuy;
import com.lxt.mall.domain.entity.GroupBuyMember;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.enums.OrderBizType;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.mapper.GroupBuyMapper;
import com.lxt.mall.domain.mapper.GroupBuyMemberMapper;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 拼团。
 *
 * <h3>业务本质与工程难点</h3>
 * 拼团是"用人数换价格"：一个人开团，凑够 N 人才能成团。
 * 技术上真正难的不是开团，而是<b>三件必须收敛的事</b>：
 * <ol>
 *   <li><b>成团判定只能发生一次</b>：第 N 个人支付成功的瞬间要把团置为已成团，
 *       而支付回调本身可能重复——所以判定必须挂在"成员状态 0→1"的条件更新上，
 *       谁改到行谁才有资格推进团状态；</li>
 *   <li><b>到期失败的竞态</b>：第 24 小时整凑满，和定时任务判定超时可能同时发生，
 *       带 {@code status = 0} 前驱条件的 UPDATE 保证只有一个能赢；</li>
 *   <li><b>失败后的资金与库存</b>：已付的必须退款、未付的必须关单、库存必须回补。
 *       这条链路绝不能只靠"有人点了退款"——必须有定时任务兜底。</li>
 * </ol>
 *
 * <h3>为什么先建订单再建团</h3>
 * 团是"多个订单的集合"，成员表通过 {@code order_no} 关联订单。
 * 先有订单（库存已扣、金额已定），团才有意义；
 * 反过来先建团再建订单，一旦下单失败就会留下一个"空团"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupBuyService {

    private final GroupBuyMapper groupBuyMapper;
    private final GroupBuyMemberMapper memberMapper;
    private final OrderMapper orderMapper;
    private final TradeService tradeService;
    private final OrderService orderService;
    private final RefundService refundService;
    private final MallProperties properties;
    private final Clock clock;

    /** 开团结果：团 ID + 团长自己的订单号。 */
    public record GroupBuyResult(Long groupId, String orderNo, int requiredMembers) {
    }

    /** 开团（团长自动成为第一个成员）。 */
    @Transactional(rollbackFor = Exception.class)
    public GroupBuyResult open(Long userId, Long skuId, int quantity) {
        OrderVO order = tradeService.createOrder(userId, skuId, quantity, null, OrderBizType.GROUP);

        GroupBuy group = new GroupBuy();
        group.setId(IdWorker.getId());
        group.setLeaderUserId(userId);
        group.setSkuId(skuId);
        group.setRequiredMembers(Math.max(2, properties.getTrade().getGroupRequiredMembers()));
        group.setPaidCount(0);
        group.setStatus(0);
        group.setExpireTime(LocalDateTime.now(clock).plus(properties.getTrade().getGroupExpire()));
        groupBuyMapper.insert(group);

        memberMapper.insert(newMember(group.getId(), userId, order.getOrderNo()));

        log.info("拼团已开启 groupId={} leader={} 需要 {} 人成团",
                group.getId(), userId, group.getRequiredMembers());
        return new GroupBuyResult(group.getId(), order.getOrderNo(), group.getRequiredMembers());
    }

    /** 参团。 */
    @Transactional(rollbackFor = Exception.class)
    public GroupBuyResult join(Long userId, Long groupId, int quantity) {
        GroupBuy group = groupBuyMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_FOUND);
        }
        if (group.getStatus() == 1) {
            throw new BusinessException(ResultCode.GROUP_CLOSED, "该拼团已成团");
        }
        if (group.getStatus() == 2) {
            throw new BusinessException(ResultCode.GROUP_CLOSED);
        }
        if (LocalDateTime.now(clock).isAfter(group.getExpireTime())) {
            throw new BusinessException(ResultCode.GROUP_EXPIRED);
        }
        Long joined = memberMapper.selectCount(Wrappers.<GroupBuyMember>lambdaQuery()
                .eq(GroupBuyMember::getGroupId, groupId)
                .eq(GroupBuyMember::getUserId, userId));
        if (joined != null && joined > 0) {
            throw new BusinessException(ResultCode.GROUP_ALREADY_JOINED);
        }

        OrderVO order = tradeService.createOrder(userId, group.getSkuId(), quantity,
                null, OrderBizType.GROUP);
        memberMapper.insert(newMember(groupId, userId, order.getOrderNo()));

        log.info("用户参团 groupId={} userId={} orderNo={}", groupId, userId, order.getOrderNo());
        return new GroupBuyResult(groupId, order.getOrderNo(), group.getRequiredMembers());
    }

    /**
     * 成员支付成功后的推进（由支付回调触发）。
     *
     * <p>只有"成员状态 0→1"改到行的那一次调用，才会给团 +1——
     * 支付回调重复抵达时，第二次拿到 0 行，团不会被重复计数。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean onMemberPaid(String orderNo) {
        GroupBuyMember member = memberMapper.selectOne(Wrappers.<GroupBuyMember>lambdaQuery()
                .eq(GroupBuyMember::getOrderNo, orderNo));
        if (member == null) {
            return false;
        }
        if (memberMapper.markPaid(member.getId()) == 0) {
            return false;
        }

        groupBuyMapper.increasePaid(member.getGroupId(), LocalDateTime.now(clock));

        GroupBuy latest = groupBuyMapper.selectById(member.getGroupId());
        if (latest != null && latest.getStatus() == 1) {
            log.info("拼团已成团 groupId={} paid={}/{}",
                    latest.getId(), latest.getPaidCount(), latest.getRequiredMembers());
        }
        return true;
    }

    /**
     * 关闭超时未成团的团。
     *
     * <p>处理顺序很重要：<b>先把团置为失败，再处理成员订单</b>。
     * 如果先退款再改状态，期间可能有新成员支付成功并触发成团判定，
     * 就会出现"团已失败、却有人付款成功"的错乱。
     *
     * @return 本次处理的失败团数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int closeExpired(int batchSize) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<GroupBuy> expired = groupBuyMapper.selectList(Wrappers.<GroupBuy>lambdaQuery()
                .eq(GroupBuy::getStatus, 0)
                .le(GroupBuy::getExpireTime, now)
                .last("LIMIT " + Math.max(1, batchSize)));
        if (expired.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (GroupBuy group : expired) {
            // 只有真正把团改成"失败"的那一次才继续处理成员
            if (groupBuyMapper.markFailed(group.getId()) == 0) {
                continue;
            }
            closed++;

            List<GroupBuyMember> members = memberMapper.selectList(
                    Wrappers.<GroupBuyMember>lambdaQuery().eq(GroupBuyMember::getGroupId, group.getId()));
            for (GroupBuyMember member : members) {
                // 刻意不在这里 try/catch：处理成员失败时应该让整个事务回滚，
                // 团回到"进行中"，下一轮任务重试。
                // 如果吞掉异常继续，团会被置为"已失败"但有人没退到款——
                // 而"已失败"的团不会被再次扫描，这笔钱就永远挂在平台上了。
                // 退款/关单本身都是幂等的（退款单唯一键 + 订单状态机），重试安全。
                handleFailedMember(member);
            }
            log.info("拼团超时关闭 groupId={} 成员 {} 人", group.getId(), members.size());
        }
        return closed;
    }

    private void handleFailedMember(GroupBuyMember member) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, member.getOrderNo()));
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.CREATED) {
            // 未支付：关单 + 回补库存
            orderService.cancelIfUnpaid(member.getOrderNo(), "拼团超时未成团");
        } else if (order.getStatus() == OrderStatus.PAID) {
            // 已支付：原路退款 + 回补库存
            refundService.refund(member.getOrderNo(), "拼团未成团自动退款", member.getUserId());
            memberMapper.markRefunded(member.getOrderNo());
        }
    }

    /** 团详情（含成员）。 */
    public GroupBuyDetail detail(Long groupId) {
        GroupBuy group = groupBuyMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_FOUND);
        }
        List<GroupBuyMember> members = memberMapper.selectList(
                Wrappers.<GroupBuyMember>lambdaQuery().eq(GroupBuyMember::getGroupId, groupId));
        return new GroupBuyDetail(group, members);
    }

    public record GroupBuyDetail(GroupBuy group, List<GroupBuyMember> members) {
    }

    private GroupBuyMember newMember(Long groupId, Long userId, String orderNo) {
        GroupBuyMember member = new GroupBuyMember();
        member.setId(IdWorker.getId());
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setOrderNo(orderNo);
        member.setStatus(0);
        return member;
    }
}
