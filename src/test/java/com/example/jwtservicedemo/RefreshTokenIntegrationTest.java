package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.dto.RefreshTokenRequest;
import com.example.jwtservicedemo.model.RefreshToken;
import com.example.jwtservicedemo.repository.RefreshTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class RefreshTokenIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private com.example.jwtservicedemo.jwt.TokenBlacklistService tokenBlacklistService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tokenBlacklistService.clear();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Login successfully issues both RS256 Access Token and Stateful Refresh Token")
    void shouldIssueRefreshTokenOnLogin() throws Exception {
        AuthRequest loginRequest = new AuthRequest("admin", "admin");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.username", is("admin")))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        String refreshTokenStr = responseNode.get("refreshToken").asText();

        // Verify state in MySQL database
        RefreshToken tokenInDb = refreshTokenRepository.findByToken(refreshTokenStr).orElseThrow();
        assertEquals("admin", tokenInDb.getUser().getUsername());
        assertFalse(tokenInDb.isRevoked());
        assertNotNull(tokenInDb.getExpiryDate());
    }

    @Test
    @DisplayName("Token Refresh: Rotates Refresh Token and issues new Access Token")
    void shouldRotateRefreshTokenAndIssueNewAccessToken() throws Exception {
        // 1. Initial Login
        AuthRequest loginRequest = new AuthRequest("admin", "admin");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String initialAccessToken = loginJson.get("token").asText();
        String initialRefreshToken = loginJson.get("refreshToken").asText();

        // 2. Call /api/auth/refresh
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(initialRefreshToken);
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", not(equalTo(initialRefreshToken))))
                .andReturn();

        JsonNode refreshJson = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String rotatedAccessToken = refreshJson.get("token").asText();
        String rotatedRefreshToken = refreshJson.get("refreshToken").asText();

        // 3. Verify Database lineage
        RefreshToken oldToken = refreshTokenRepository.findByToken(initialRefreshToken).orElseThrow();
        assertTrue(oldToken.isRevoked(), "Old refresh token must be revoked after rotation");
        assertEquals(rotatedRefreshToken, oldToken.getReplacedByToken(), "Old token must track the replacement token");

        RefreshToken newToken = refreshTokenRepository.findByToken(rotatedRefreshToken).orElseThrow();
        assertFalse(newToken.isRevoked(), "New rotated refresh token must be active");

        // 4. Verify newly issued access token can access protected endpoints
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Welcome to Admin Area")));
    }

    @Test
    @DisplayName("Security Defense: Reusing a rotated refresh token triggers reuse detection and revokes ALL sessions")
    void shouldDetectRefreshTokenReuseAndRevokeAllSessions() throws Exception {
        // 1. Login
        AuthRequest loginRequest = new AuthRequest("admin", "admin");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String originalRefreshToken = loginJson.get("refreshToken").asText();

        // 2. Legitimate rotation
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(originalRefreshToken);
        MvcResult rotateResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String newRefreshToken = objectMapper.readTree(rotateResult.getResponse().getContentAsString()).get("refreshToken").asText();

        // 3. Attacker reuses the original (already rotated) token -> ALARM!
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Compromised token reuse detected")));

        // 4. Verify that even the legitimate new refresh token was revoked to neutralize the breach
        RefreshToken activeToken = refreshTokenRepository.findByToken(newRefreshToken).orElseThrow();
        assertTrue(activeToken.isRevoked(), "Legitimate token must also be revoked once breach is detected");

        // 5. Subsequent refresh attempts fail
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(newRefreshToken))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Logout: Immediately blacklists access token and revokes refresh token")
    void shouldBlacklistAccessTokenAndRevokeRefreshTokenOnLogout() throws Exception {
        // 1. Login
        AuthRequest loginRequest = new AuthRequest("admin", "admin");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.get("token").asText();
        String refreshToken = loginJson.get("refreshToken").asText();

        // 2. Verify access token works before logout
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 3. Logout with Bearer token & refresh token in body
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Successfully logged out")));

        // 4. Immediate Access Token Revocation: Using same access token now returns 401 Unauthorized
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // 5. Refresh token is revoked
        RefreshToken revokedInDb = refreshTokenRepository.findByToken(refreshToken).orElseThrow();
        assertTrue(revokedInDb.isRevoked());
    }
}
