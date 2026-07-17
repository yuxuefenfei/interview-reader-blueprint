package com.example.interviewreader;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "interview-reader.security.enabled=true",
        "interview-reader.security.username=tester",
        "interview-reader.security.password=secret",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewReaderAuthTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedApiRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/reader/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store"))
                .andExpect(result -> assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error").value("请先登录"));
    }

    @Test
    void unauthenticatedShellAssetsRemainAvailable() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }
    @Test
    void deepLinkedSpaRoutesReturnTheApplicationShell() throws Exception {
        mockMvc.perform(get("/admin/documents/9a9c5fc6-d310-44fe-aff4-cca83bf28d12"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/reader/documents/9a9c5fc6-d310-44fe-aff4-cca83bf28d12"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void loginCreatesSessionCookieForProtectedApi() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "tester",
                                  "password": "wrong"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        var response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "tester",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("IR_SESSION"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("tester"))
                .andReturn()
                .getResponse();
        var session = response.getCookie("IR_SESSION");
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/reader/documents").cookie(session))
                .andExpect(status().isOk());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        var session = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "tester",
                                  "password": "secret"
                                }
                                """))
                .andReturn()
                .getResponse()
                .getCookie("IR_SESSION");
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store"))
                .andExpect(jsonPath("$.authenticated").value(true));

        mockMvc.perform(post("/api/auth/logout").cookie(session))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/reader/documents").cookie(new Cookie("IR_SESSION", session.getValue())))
                .andExpect(status().isUnauthorized());
    }
}

