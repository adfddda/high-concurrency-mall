package com.lxt.mall.trade.dto;

import com.lxt.mall.domain.entity.GroupBuy;
import com.lxt.mall.domain.entity.GroupBuyMember;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 拼团详情视图。 */
@Data
public class GroupBuyVO implements Serializable {

    private Long groupId;

    private Long skuId;

    private Long leaderUserId;

    private Integer requiredMembers;

    private Integer paidCount;

    /** 0 进行中 / 1 已成团 / 2 已失败 */
    private Integer status;

    private String statusDesc;

    private LocalDateTime expireTime;

    private LocalDateTime completeTime;

    private List<Member> members;

    @Data
    public static class Member implements Serializable {
        private Long userId;
        private String orderNo;
        private Integer status;
        private String statusDesc;
    }

    public static GroupBuyVO from(GroupBuy group, List<GroupBuyMember> members) {
        GroupBuyVO vo = new GroupBuyVO();
        vo.setGroupId(group.getId());
        vo.setSkuId(group.getSkuId());
        vo.setLeaderUserId(group.getLeaderUserId());
        vo.setRequiredMembers(group.getRequiredMembers());
        vo.setPaidCount(group.getPaidCount());
        vo.setStatus(group.getStatus());
        vo.setStatusDesc(switch (group.getStatus() == null ? -1 : group.getStatus()) {
            case 0 -> "进行中";
            case 1 -> "已成团";
            case 2 -> "已失败";
            default -> "未知";
        });
        vo.setExpireTime(group.getExpireTime());
        vo.setCompleteTime(group.getCompleteTime());
        vo.setMembers(members.stream().map(m -> {
            Member vm = new Member();
            vm.setUserId(m.getUserId());
            vm.setOrderNo(m.getOrderNo());
            vm.setStatus(m.getStatus());
            vm.setStatusDesc(switch (m.getStatus() == null ? -1 : m.getStatus()) {
                case 0 -> "待支付";
                case 1 -> "已支付";
                case 2 -> "已退款";
                default -> "未知";
            });
            return vm;
        }).toList());
        return vo;
    }
}
