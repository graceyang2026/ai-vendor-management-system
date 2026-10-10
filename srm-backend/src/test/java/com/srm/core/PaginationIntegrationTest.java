package com.srm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分页真实生效回归测试（连接 H2 真实库，非 mock mapper）。
 * 背景：MybatisPlusConfig 若缺失 PaginationInnerInterceptor，selectPage 会退化为全表查询、
 * total 恒 0，且单测因 mock selectPage 无法发现。本测试直接打 /users 端点验证 LIMIT 与 COUNT 均生效。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class PaginationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String loginAdminToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private void insertUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("Any@12345"));
        user.setRealName("分页测试用户");
        user.setRole("STAFF");
        user.setEnabled(true);
        userMapper.insert(user);
    }

    @Test
    void userPageQueryAppliesLimitAndReturnsRealTotal() throws Exception {
        // 造足量数据：seeder 已有 3 条，再插 5 条，确保总数 > 2 页
        for (int i = 1; i <= 5; i++) {
            insertUser("pageuser" + i);
        }
        long realTotal = userMapper.selectCount(null);
        assertThat(realTotal).isGreaterThan(4L);

        String token = loginAdminToken();

        // page_size=2 必须只返回 2 条（LIMIT 生效），且 total 为真实总数（COUNT 生效）
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.total").value((int) realTotal))
                .andExpect(jsonPath("$.data.page_size").value(2));

        // 第二页与第一页不重复，证明 OFFSET 也生效
        String firstPageBody = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("page_size", "2"))
                .andReturn().getResponse().getContentAsString();
        String secondPageBody = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "2").param("page_size", "2"))
                .andReturn().getResponse().getContentAsString();

        int firstId = objectMapper.readTree(firstPageBody).get("data").get("list").get(0).get("id").asInt();
        int secondFirstId = objectMapper.readTree(secondPageBody).get("data").get("list").get(0).get("id").asInt();
        assertThat(secondFirstId).isNotEqualTo(firstId);
    }
}
