package com.lxt.mall.config;

import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口文档可用性测试。
 *
 * <p>为什么值得为"文档"写测试：springdoc 与 Spring Boot 的版本一旦不匹配，
 * 典型表现是应用<b>启动失败</b>或 {@code /v3/api-docs} 返回 500——
 * 这类问题在编译期完全看不出来，只有真正请求一次才会暴露。
 *
 * <p>断言里刻意检查了具体路径，而不是只断言 200：
 * 如果只判断状态码，一个空的 spec 也能通过。
 */
@AutoConfigureMockMvc
class OpenApiDocsTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("OpenAPI 文档可公开访问，且包含秒杀下单接口")
    void apiDocsExposeBusinessEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("高并发交易系统 API"))
                .andExpect(jsonPath("$.paths['/api/seckill/activities/{activityId}/order']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/seckill/{activityId}/reconcile']").exists());
    }
}
