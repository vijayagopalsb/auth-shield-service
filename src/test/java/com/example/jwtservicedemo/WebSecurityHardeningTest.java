package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
public class WebSecurityHardeningTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("CORS: Whitelisted origin (http://localhost:3000) receives Access-Control-Allow-Origin")
    void shouldAllowCorsFromWhitelistedOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("CORS: Untrusted origin does not receive Access-Control-Allow-Origin header")
    void shouldDenyCorsFromUntrustedOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://malicious-website.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("OWASP Headers: Response includes nosniff, DENY frame options, CSP, and Referrer-Policy")
    void shouldIncludeOwaspSecurityHeaders() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", is("nosniff")))
                .andExpect(header().string("X-Frame-Options", is("DENY")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Referrer-Policy", is("strict-origin-when-cross-origin")));
    }

    @Test
    @DisplayName("Password Policy: Registration rejects weak password missing complexity requirements")
    void shouldRejectWeakPasswordWithoutComplexity() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .username("weak_pwd_user")
                .password("weakpassword123") // Missing uppercase and special character
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.validationErrors.password",
                        containsString("Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character")));
    }

    @Test
    @DisplayName("Password Policy: Registration rejects passwords under 8 characters")
    void shouldRejectShortPassword() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .username("short_pwd_user")
                .password("Short#1") // 7 characters
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password", containsString("Password must be between 8 and 100 characters")));
    }

    @Test
    @DisplayName("Password Policy: Registration succeeds with OWASP compliant strong password")
    void shouldAcceptStrongCompliantPassword() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .username("strong_pwd_user")
                .password("ValidSecurity#Pass2026")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is("strong_pwd_user")))
                .andExpect(jsonPath("$.role", is("USER")));
    }
}
