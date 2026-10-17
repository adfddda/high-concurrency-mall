package com.lxt.mall.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路验证：注册 → 登录 → 访问受保护接口 → 退出 → 令牌立即失效。
 *
 * <p>重点验证的是<b>JWT 无状态带来的那个经典问题</b>：
 * 令牌一旦签发，服务端就"忘不掉"它。用户点了退出，如果不做额外处理，
 * 那把旧令牌在过期之前仍然完全有效——这是很多真实系统的漏洞。
 * 黑名单机制必须让它在退出后<b>立刻</b>失效，而不是等 2 小时自然过期。
 */
@AutoConfigureMockMvc
class AuthFlowTest extends BaseIntegrationTest {

    /** 登录接口 ResultCode.UNAUTHORIZED */
    private static final int CODE_UNAUTHORIZED = 40100;
    private static final int CODE_SUCCESS = 0;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndLogin(String username) throws Exception {
        String password = "test123456";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"认证测试"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                .andReturn();

        JsonNode node = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return node.path("data").path("token").asText();
    }

    @Test
    @DisplayName("登录后可访问受保护接口，退出后同一令牌立即失效")
    void logoutInvalidatesTokenImmediately() throws Exception {
        String username = "authtest" + UUID.randomUUID().toString().substring(0, 8);
        String token = registerAndLogin(username);
        assertThat(token).isNotBlank();

        // 带令牌可以正常访问
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                .andExpect(jsonPath("$.data.username").value(username));

        // 退出登录
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

        // 同一个令牌必须立刻失效——这才叫"主动退出"
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_UNAUTHORIZED));
    }

    @Test
    @DisplayName("黑名单 key 的 TTL 等于令牌剩余有效期，不会无限膨胀")
    void blacklistEntryHasBoundedTtl() throws Exception {
        String username = "ttltest" + UUID.randomUUID().toString().substring(0, 8);
        String token = registerAndLogin(username);

        // 解析出 jti，直接检查 Redis 里的黑名单 key
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 扫描所有黑名单 key，确认 TTL 是有限的（<= token 有效期 5 分钟）
        var keys = redis.keys("auth:token:blacklist:*");
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            Long ttl = redis.getExpire(key);
            assertThat(ttl)
                    .as("黑名单 key 必须有 TTL（取值为令牌剩余有效期），否则会永久膨胀")
                    .isNotNull()
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(5 * 60L);
        }
    }

    @Test
    @DisplayName("无令牌 / 伪造令牌 一律拒绝，且不泄露失败原因")
    void invalidTokenIsRejected() throws Exception {
        // 完全没有令牌
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_UNAUTHORIZED));

        // 结构像 JWT 但签名是假的
        String fake = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiaXNzIjoiaGlnaC1jb25jdXJyZW5jeS1tYWxsIn0.forged-signature";
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fake))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("登录状态无效，请重新登录"));
    }

    @Test
    @DisplayName("注册接口的密码强度与用户名格式校验生效")
    void registrationValidationWorks() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"has space","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("重复注册被唯一索引拦下，返回明确错误码")
    void duplicateRegistrationIsRejected() throws Exception {
        String username = "dup" + UUID.randomUUID().toString().substring(0, 8);
        registerAndLogin(username);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"test123456"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一个错误码，避免用户名枚举")
    void wrongPasswordAndUnknownUserReturnSameCode() throws Exception {
        String username = "enum" + UUID.randomUUID().toString().substring(0, 8);
        registerAndLogin(username);

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"wrong-password"}
                                """.formatted(username)))
                .andReturn();

        MvcResult unknownUser = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"definitely-not-exists","password":"test123456"}
                                """))
                .andReturn();

        JsonNode a = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(unknownUser.getResponse().getContentAsString());

        assertThat(a.path("code").asInt())
                .as("两种情况必须返回相同错误码，否则就成了免费的【用户名枚举】接口")
                .isEqualTo(b.path("code").asInt())
                .isEqualTo(10003);
        assertThat(a.path("message").asText()).isEqualTo(b.path("message").asText());
    }

    @Test
    @DisplayName("公开接口无需令牌即可访问")
    void publicEndpointsAreAccessible() throws Exception {
        mockMvc.perform(get("/api/product/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

        mockMvc.perform(get("/api/seckill/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
    }
}
