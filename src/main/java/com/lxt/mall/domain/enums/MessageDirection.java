package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 消息方向：区分「生产台账」和「消费幂等记录」。 */
public enum MessageDirection {

    PRODUCE(1, "生产"),
    CONSUME(2, "消费");

    @EnumValue
    private final int code;
    private final String desc;

    MessageDirection(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
