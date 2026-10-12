package com.example.jwtservicedemo.ratelimit;

import com.example.jwtservicedemo.controller.AuthController;
import com.example.jwtservicedemo.jwt.RsaJwtService;
import com.example.jwtservicedemo.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RateLimitingFilterIntegrationTest {

    private MockMvc mockMvc;
    private RateLimitingService rateLimitingService;

    @BeforeEach
    void setUp() {
        UserService userService = new UserService();
        userService.initDefaultUsers();
        RsaJwtService rsaJwtService = new RsaJwtService();
        AuthController authController = new AuthController(userService, rsaJwtService);

        // Auth capacity: 5 tokens, General capacity: 30 tokens
        rateLimitingService = new RateLimitingService(5, 5, 60, 30, 30, 60);
        RateLimitingFilter rateLimitingFilter = new RateLimitingFilter(rateLimitingService);

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .addFilters(rateLimitingFilter)
                .build();
    }

    @Test
    @DisplayName("Should include X-Rate-Limit-Remaining header on successful response")
    void shouldIncludeRateLimitHeaders() throws Exception {
        String loginJson = "{\"username\":\"admin\",\"password\":\"admin\"}";

        mockMvc.perform(post("/api/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("192.168.10.1");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Rate-Limit-Remaining"))
                .andExpect(header().string("X-Rate-Limit-Remaining", is("4")));
    }

    @Test
    @DisplayName("Should return 429 Too Many Requests when auth rate limit (5) is exceeded")
    void shouldReturn429WhenLimitExceeded() throws Exception {
        String testIp = "192.168.10.50";
        String loginJson = "{\"username\":\"admin\",\"password\":\"admin\"}";

        // First 5 requests should pass
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(testIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson))
                    .andExpect(status().isOk());
        }

        // 6th request from the same IP must be rejected with 429
        mockMvc.perform(post("/api/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(testIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-Rate-Limit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Rate-Limit-Retry-After-Seconds"))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.message", containsString("Rate limit exceeded")))
                .andExpect(jsonPath("$.retryAfterSeconds", greaterThan(0)));
    }

    @Test
    @DisplayName("Should respect X-Forwarded-For header when behind reverse proxy")
    void shouldRespectXForwardedForHeader() throws Exception {
        String proxyClientIp = "203.0.113.195";
        String loginJson = "{\"username\":\"admin\",\"password\":\"admin\"}";

        // Send 5 requests using X-Forwarded-For
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", proxyClientIp + ", 10.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson))
                    .andExpect(status().isOk());
        }

        // 6th request from proxy client IP should be throttled
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", proxyClientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)));
    }

    @Test
    @DisplayName("Should rate limit general endpoints with relaxed limits")
    void shouldRateLimitGeneralEndpoints() throws Exception {
        String generalIp = "192.168.10.99";

        // General endpoints have capacity 30
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/auth/users")
                            .with(req -> {
                                req.setRemoteAddr(generalIp);
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        // 31st request from same IP should get 429
        mockMvc.perform(get("/api/auth/users")
                        .with(req -> {
                            req.setRemoteAddr(generalIp);
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)));
    }
}
