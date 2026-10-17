package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.MqMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqMessageMapper extends BaseMapper<MqMessage> {
}
