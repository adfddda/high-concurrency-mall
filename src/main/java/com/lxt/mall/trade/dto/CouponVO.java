package com.lxt.mall.trade.dto;

import com.lxt.mall.domain.entity.Coupon;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户优惠券视图。 */
@Data
public class CouponVO implements Serializable {

    private Long id;

    private String couponNo;

    private Long templateId;

    /** 0 未使用 / 1 已使用 / 2 已过期 */
    private Integer status;

    private String statusDesc;

    private String orderNo;

    private LocalDateTime expireTime;

    /** 门槛与面额从模板带出来，前端直接展示"满 100 减 20"。 */
    private BigDecimal thresholdAmount;

    private BigDecimal discountAmount;

    public static CouponVO from(Coupon coupon) {
        CouponVO vo = new CouponVO();
        vo.setId(coupon.getId());
        vo.setCouponNo(coupon.getCouponNo());
        vo.setTemplateId(coupon.getTemplateId());
        vo.setStatus(coupon.getStatus());
        vo.setStatusDesc(switch (coupon.getStatus() == null ? -1 : coupon.getStatus()) {
            case 0 -> "未使用";
            case 1 -> "已使用";
            case 2 -> "已过期";
            default -> "未知";
        });
        vo.setOrderNo(coupon.getOrderNo());
        vo.setExpireTime(coupon.getExpireTime());
        return vo;
    }
}
