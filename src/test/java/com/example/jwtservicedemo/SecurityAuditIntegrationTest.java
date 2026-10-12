package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.dto.AuthResponse;
import com.example.jwtservicedemo.dto.RefreshTokenRequest;
import com.example.jwtservicedemo.dto.RoleUpdateRequest;
import com.example.jwtservicedemo.model.RefreshToken;
import com.example.jwtservicedemo.model.SecurityAuditLog;
import com.example.jwtservicedemo.model.User;
import com.example.jwtservicedemo.repository.SecurityAuditLogRepository;
import com.example.jwtservicedemo.service.RefreshTokenService;
import com.example.jwtservicedemo.service.UserService;
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

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
public class SecurityAuditIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserService userService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private SecurityAuditLogRepository auditLogRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUDIT_USER = "audit_test_user";
    private static final String AUDIT_PASS = "AuditPass#123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Clear audit repository for clean assertions
        auditLogRepository.deleteAll();

        // Ensure clean test user
        if (userService.findByUsername(AUDIT_USER).isEmpty()) {
            userService.register(User.builder()
                    .username(AUDIT_USER)
                    .password(AUDIT_PASS)
                    .role("USER")
                    .build());
        } else {
            userService.unlockAccount(AUDIT_USER);
            userService.updateRole(AUDIT_USER, "USER");
        }
    }

    private String obtainToken(String username, String password) throws Exception {
        AuthRequest req = new AuthRequest(username, password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        return resp.getToken();
    }

    @Test
    @DisplayName("Audit Event: LOGIN_SUCCESS is emitted and saved on valid credentials")
    void shouldLogLoginSuccessEvent() throws Exception {
        AuthRequest req = new AuthRequest(AUDIT_USER, AUDIT_PASS);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        assertFalse(logs.isEmpty(), "Expected at least one audit log entry for user");
        SecurityAuditLog log = logs.get(0);
        assertEquals("LOGIN_SUCCESS", log.getEventType());
        assertEquals(AUDIT_USER, log.getUsername());
        assertEquals("SUCCESS", log.getStatus());
        assertTrue(log.getDetails().contains("successfully"));
    }

    @Test
    @DisplayName("Audit Event: LOGIN_FAILED is emitted and saved on incorrect password")
    void shouldLogLoginFailedEvent() throws Exception {
        AuthRequest req = new AuthRequest(AUDIT_USER, "WrongPassword#999");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        assertFalse(logs.isEmpty());
        SecurityAuditLog log = logs.get(0);
        assertEquals("LOGIN_FAILED", log.getEventType());
        assertEquals(AUDIT_USER, log.getUsername());
        assertEquals("FAILURE", log.getStatus());
        assertTrue(log.getDetails().contains("Attempt 1 of 5"));
    }

    @Test
    @DisplayName("Audit Event: ACCOUNT_LOCKED is emitted when 5 failed logins lock the user")
    void shouldLogAccountLockedEvent() throws Exception {
        for (int i = 1; i <= 5; i++) {
            AuthRequest req = new AuthRequest(AUDIT_USER, "WrongPass" + i);
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));
        }

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        boolean hasLockEvent = logs.stream().anyMatch(l -> "ACCOUNT_LOCKED".equals(l.getEventType()) && "ALERT".equals(l.getStatus()));
        assertTrue(hasLockEvent, "Should have emitted ACCOUNT_LOCKED alert event");
    }

    @Test
    @DisplayName("Audit Event: TOKEN_REUSE_BREACH is emitted on replay attack of revoked refresh token")
    void shouldLogTokenReuseBreachEvent() {
        RefreshToken token = refreshTokenService.createRefreshToken(AUDIT_USER);

        // First rotation (valid)
        RefreshToken rotated = refreshTokenService.rotateRefreshToken(token.getToken());
        assertNotNull(rotated);

        // Second rotation with old token (BREACH!)
        assertThrows(SecurityException.class, () -> {
            refreshTokenService.rotateRefreshToken(token.getToken());
        });

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        boolean hasBreach = logs.stream().anyMatch(l -> "TOKEN_REUSE_BREACH".equals(l.getEventType()) && "ALERT".equals(l.getStatus()));
        assertTrue(hasBreach, "Should have emitted TOKEN_REUSE_BREACH alert event");
    }

    @Test
    @DisplayName("Audit Event: ROLE_UPDATED is emitted when admin alters user privileges")
    void shouldLogRoleUpdatedEvent() throws Exception {
        String adminToken = obtainToken("admin", "admin");

        RoleUpdateRequest updateReq = new RoleUpdateRequest("MANAGER");
        mockMvc.perform(put("/api/auth/users/" + AUDIT_USER + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        boolean hasRoleUpdate = logs.stream().anyMatch(l -> "ROLE_UPDATED".equals(l.getEventType()) && "SUCCESS".equals(l.getStatus()));
        assertTrue(hasRoleUpdate, "Should have emitted ROLE_UPDATED event");
    }

    @Test
    @DisplayName("Audit Event: LOGOUT is emitted when user logs out")
    void shouldLogLogoutEvent() throws Exception {
        String userToken = obtainToken(AUDIT_USER, AUDIT_PASS);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        List<SecurityAuditLog> logs = auditLogRepository.findByUsernameOrderByTimestampDesc(AUDIT_USER);
        boolean hasLogout = logs.stream().anyMatch(l -> "LOGOUT".equals(l.getEventType()));
        assertTrue(hasLogout, "Should have emitted LOGOUT event");
    }

    @Test
    @DisplayName("GET /api/auth/audit-logs: Admin can view audit logs; USER is forbidden; Anonymous is unauthorized")
    void shouldEnforceAdminSecurityOnAuditLogsEndpoint() throws Exception {
        // 1. Generate some audit activity
        AuthRequest req = new AuthRequest(AUDIT_USER, AUDIT_PASS);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 2. Anonymous access -> 401 Unauthorized
        mockMvc.perform(get("/api/auth/audit-logs"))
                .andExpect(status().isUnauthorized());

        // 3. User access -> 403 Forbidden
        String userToken = obtainToken(AUDIT_USER, AUDIT_PASS);
        mockMvc.perform(get("/api/auth/audit-logs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 4. Admin access -> 200 OK with list of audit records
        String adminToken = obtainToken("admin", "admin");
        mockMvc.perform(get("/api/auth/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].eventType", notNullValue()))
                .andExpect(jsonPath("$[0].username", notNullValue()))
                .andExpect(jsonPath("$[0].status", notNullValue()))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()));
    }
}
