package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户。
 *
 * <p>create_time / update_time 交给数据库默认值填充（DDL 里写了
 * DEFAULT CURRENT_TIMESTAMP(3) 与 ON UPDATE），应用侧用
 * {@link FieldStrategy#NEVER} 明确排除，好处是：
 * 全库时间只有一个来源（数据库时钟），避免多实例应用服务器之间时钟不一致
 * 导致 create_time 乱序——这在排查"订单创建时间早于活动开始时间"这类问题时很致命。
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文，任何接口都不会把它返回给前端。 */
    private String password;

    private String nickname;

    private String phone;

    /** 1 正常 / 0 禁用 */
    private Integer status;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
