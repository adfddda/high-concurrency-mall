package com.lxt.mall.seckill.dto;

import com.lxt.mall.domain.entity.SeckillReservation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 预约 / 中签结果视图。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillReservationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long activityId;

    private Long userId;

    /** 0 已预约 / 1 已中签 / 2 未中签 / 3 资格已使用 */
    private Integer status;

    private String statusDesc;

    /** 只有中签时才有值，下单时作为凭证传给 /order 接口。 */
    private String qualifyCode;

    public static SeckillReservationVO from(SeckillReservation r) {
        if (r == null) {
            return null;
        }
        return new SeckillReservationVO(r.getActivityId(), r.getUserId(),
                r.getStatus(), descOf(r.getStatus()), r.getQualifyCode());
    }

    private static String descOf(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "已预约";
            case 1 -> "已中签";
            case 2 -> "未中签";
            case 3 -> "资格已使用";
            default -> "未知";
        };
    }
}
