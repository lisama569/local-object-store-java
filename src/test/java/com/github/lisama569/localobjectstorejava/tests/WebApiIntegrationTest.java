package com.github.lisama569.localobjectstorejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 层端到端：错误类型到 HTTP 状态码的映射、租户头、完整分片流程。
 * 使用独立临时目录，避免污染默认数据目录。
 */
@SpringBootTest(properties = {
        "objectstore.root-dir=target/test-data/webapi",
        "objectstore.capacity-bytes=4096",
        "objectstore.default-max-part-size=1024"
})
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void multipartFlowAndErrorMapping() throws Exception {
        byte[] p1 = "hello-".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "world!".getBytes(StandardCharsets.UTF_8);
        byte[] full = "hello-world!".getBytes(StandardCharsets.UTF_8);

        // 发起
        String body = "{\"objectKey\":\"docs/readme\",\"totalParts\":2}";
        String response = mvc.perform(post("/api/objects/multipart")
                        .header("X-Tenant-Id", "team-web")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uploadId = response.replaceAll(".*\"uploadId\":\"([^\"]+)\".*", "$1");

        // 分片（乱序：先传 2）
        mvc.perform(put("/api/objects/multipart/" + uploadId + "/parts/2")
                        .header("X-Tenant-Id", "team-web")
                        .content(p2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumber").value(2));
        mvc.perform(put("/api/objects/multipart/" + uploadId + "/parts/1")
                        .header("X-Tenant-Id", "team-web")
                        .content(p1))
                .andExpect(status().isOk());

        // 完成
        mvc.perform(post("/api/objects/multipart/" + uploadId + "/complete")
                        .header("X-Tenant-Id", "team-web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("docs/readme"));

        // 读取内容一致
        mvc.perform(get("/api/objects").header("X-Tenant-Id", "team-web")
                        .param("objectKey", "docs/readme"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(full));

        // 越权：另一租户 -> 404，不泄露存在性
        mvc.perform(get("/api/objects").header("X-Tenant-Id", "intruder")
                        .param("objectKey", "docs/readme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorType").value("NOT_FOUND"));

        // 缺少租户头 -> 400 INVALID_ARGUMENT
        mvc.perform(get("/api/objects").param("objectKey", "docs/readme"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").value("INVALID_ARGUMENT"));
    }
}
