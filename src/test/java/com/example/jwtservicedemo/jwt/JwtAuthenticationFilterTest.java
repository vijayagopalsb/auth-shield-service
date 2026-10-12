package com.example.jwtservicedemo.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class JwtAuthenticationFilterTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RsaJwtService rsaJwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Public Endpoint: POST /api/auth/login succeeds without Authorization header")
    void shouldAllowLoginWithoutToken() throws Exception {
        String loginJson = "{\"username\":\"admin\",\"password\":\"admin\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.username", is("admin")));
    }

    @Test
    @DisplayName("Protected Endpoint: GET /api/auth/users is REJECTED (401) when Authorization header is missing")
    void shouldRejectUsersEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.path", is("/api/auth/users")));
    }

    @Test
    @DisplayName("Protected Endpoint: GET /api/auth/profile is REJECTED (401) when Authorization header is missing")
    void shouldRejectProfileEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.path", is("/api/auth/profile")));
    }

    @Test
    @DisplayName("Protected Endpoint: REJECTED (401) when token is forged or invalid")
    void shouldRejectInvalidToken() throws Exception {
        String invalidToken = "Bearer invalid.jwt.token12345";

        mockMvc.perform(get("/api/auth/users")
                        .header("Authorization", invalidToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Protected Endpoint: GET /api/auth/users SUCCEEDS (200) with valid JWT Bearer token")
    void shouldAllowUsersEndpointWithValidToken() throws Exception {
        String validToken = rsaJwtService.generateToken("admin");

        mockMvc.perform(get("/api/auth/users")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @DisplayName("Protected Endpoint: GET /api/auth/profile SUCCEEDS (200) and returns authenticated profile")
    void shouldReturnProfileWithValidToken() throws Exception {
        String validToken = rsaJwtService.generateToken("admin");

        mockMvc.perform(get("/api/auth/profile")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
