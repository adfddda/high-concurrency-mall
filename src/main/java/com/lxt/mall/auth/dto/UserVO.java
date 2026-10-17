package com.lxt.mall.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对外暴露的用户视图。【绝不包含 password 字段】。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String phone;
}
