package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 秒杀活动状态。 */
public enum ActivityStatus {

    NOT_STARTED(0, "未开始"),
    RUNNING(1, "进行中"),
    FINISHED(2, "已结束");

    @EnumValue
    private final int code;
    private final String desc;

    ActivityStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ActivityStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (ActivityStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知活动状态: " + code);
    }
}
