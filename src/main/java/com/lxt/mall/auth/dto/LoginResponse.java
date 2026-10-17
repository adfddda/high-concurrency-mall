package com.lxt.mall.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    /** 有效期秒数，前端可据此提前刷新。 */
    private long expiresIn;

    private Long userId;

    private String username;

    private String nickname;
}
