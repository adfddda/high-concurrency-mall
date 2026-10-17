package com.lxt.mall.api;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口契约测试：守的是**横切逻辑**，不是业务逻辑。
 *
 * <p>项目里绝大多数测试都在 Service 层，鉴权、限流、参数校验这些
 * "每个接口都会经过"的逻辑反而没有测试。它们的失效不会让任何业务用例变红，
 * 但会直接变成线上事故（越权、刷接口、脏数据）。
 *
 * <p>这组用例刻意用 MockMvc 从 HTTP 入口打进去，因为拦截器 / AOP / 异常处理器
 * 只有在完整的请求链路里才会真正生效。
 */
@AutoConfigureMockMvc
class ApiContractTest extends BaseIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    /** 用种子数据里的普通用户登录，拿到真实 JWT。 */
    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
        String token = node.path("data").path("token").asText();
        assertThat(token).as("登录应当成功，否则后续断言没有意义").isNotBlank();
        return token;
    }

    private int codeOf(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    @Test
    @DisplayName("公开接口无需令牌即可访问")
    void publicEndpointWorksWithoutToken() throws Exception {
        mockMvc.perform(get("/api/product/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("受保护接口缺少令牌时返回 40100，而不是放行")
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/order/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("普通用户调用运维接口返回 40300（角色校验生效）")
    void adminEndpointRejectsNormalUser() throws Exception {
        String token = login("testuser1");

        mockMvc.perform(get("/api/admin/cache/product/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("参数校验：数量为 0 时返回 40000，不会进入业务逻辑")
    void invalidBodyRejectedBeforeBusinessLogic() throws Exception {
        String token = login("testuser1");

        mockMvc.perform(post("/api/trade/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":3001,\"quantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("秒杀接口超过阈值后被限流（42900）")
    void seckillEndpointIsRateLimited() throws Exception {
        String token = login("testuser1");
        List<Integer> codes = new ArrayList<>();

        // 规则是 5 次/秒（USER + IP）。这里连打 7 次，至少有一次应被限流。
        // 用不存在的活动：业务会快速失败，不会真的扣库存、发消息，
        // 这样测试只验证"限流这一层"，不引入其它副作用。
        for (int i = 0; i < 7; i++) {
            MvcResult result = mockMvc.perform(post("/api/seckill/activities/999999/order")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            codes.add(codeOf(result));
        }

        assertThat(codes)
                .as("应当出现限流拒绝，实际返回码=%s", codes)
                .contains(42900);
    }
}
