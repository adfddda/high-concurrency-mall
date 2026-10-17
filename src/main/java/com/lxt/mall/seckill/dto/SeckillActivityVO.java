package com.lxt.mall.seckill.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SeckillActivityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String activityName;
    private Long productId;
    private String productTitle;
    private String coverImg;
    private BigDecimal originalPrice;
    private BigDecimal seckillPrice;
    private Integer totalStock;
    private Integer availableStock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private String statusDesc;

    /**
     * 服务端当前时间。
     *
     * <p>必须返回给前端用于<b>倒计时校准</b>：客户端的本地时间不可信
     * （时区设置错误、用户手动改时间），如果前端直接用自己的时钟算倒计时，
     * 会出现"页面上显示还没开始，一点就提示已结束"这种体验问题。
     * 正确做法是用服务端时间 + 与客户端时间的差值来计算。
     */
    private LocalDateTime serverTime;
}
