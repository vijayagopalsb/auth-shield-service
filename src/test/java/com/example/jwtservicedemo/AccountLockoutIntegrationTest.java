package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.model.User;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class AccountLockoutIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserService userService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TEST_USER = "lockout_target_user";
    private static final String TEST_PASS = "TargetPass#123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Ensure clean test user state
        if (userService.findByUsername(TEST_USER).isEmpty()) {
            userService.register(User.builder()
                    .username(TEST_USER)
                    .password(TEST_PASS)
                    .role("USER")
                    .build());
        } else {
            userService.unlockAccount(TEST_USER);
        }
    }

    @Test
    @DisplayName("Failed logins (1-4) return 401 Unauthorized and increment failed attempts counter")
    void shouldIncrementFailedAttemptsBeforeLockout() throws Exception {
        AuthRequest wrongRequest = new AuthRequest(TEST_USER, "WrongPass#1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("Invalid username or password")));

        User user = userService.findByUsername(TEST_USER).orElseThrow();
        assertEquals(1, user.getFailedAttempts());
        assertNull(user.getLockTime());
    }

    @Test
    @DisplayName("5th consecutive failed attempt locks the account and returns HTTP 423 Locked")
    void shouldLockAccountOnFifthFailedAttempt() throws Exception {
        AuthRequest wrongRequest = new AuthRequest(TEST_USER, "WrongPass#BruteForce");

        // First 4 attempts return 401
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongRequest)))
                    .andExpect(status().isUnauthorized());
        }

        // 5th attempt triggers lockout -> 423 Locked
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("Locked")))
                .andExpect(jsonPath("$.message", containsString("has been locked for 15 minutes due to 5 consecutive failed login attempts")))
                .andExpect(jsonPath("$.remainingMinutes", is(15)));

        User user = userService.findByUsername(TEST_USER).orElseThrow();
        assertEquals(5, user.getFailedAttempts());
        assertNotNull(user.getLockTime());
    }

    @Test
    @DisplayName("Attempts while locked are rejected with 423 Locked even if correct password is provided")
    void shouldRejectAttemptsWhileLockedEvenWithCorrectPassword() throws Exception {
        // Explicitly lock the account
        userService.lockAccount(TEST_USER);

        AuthRequest correctRequest = new AuthRequest(TEST_USER, TEST_PASS);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("Locked")))
                .andExpect(jsonPath("$.message", containsString("is temporarily locked")));
    }

    @Test
    @DisplayName("Successful login resets failed attempts counter back to 0")
    void shouldResetFailedAttemptsOnSuccessfulLogin() throws Exception {
        AuthRequest wrongRequest = new AuthRequest(TEST_USER, "WrongPass#1");

        // 2 failed attempts
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongRequest)))
                    .andExpect(status().isUnauthorized());
        }

        User userAfterFails = userService.findByUsername(TEST_USER).orElseThrow();
        assertEquals(2, userAfterFails.getFailedAttempts());

        // Successful login
        AuthRequest correctRequest = new AuthRequest(TEST_USER, TEST_PASS);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()));

        User userAfterSuccess = userService.findByUsername(TEST_USER).orElseThrow();
        assertEquals(0, userAfterSuccess.getFailedAttempts());
        assertNull(userAfterSuccess.getLockTime());
    }

    @Test
    @DisplayName("Account automatically unlocks once the 15-minute lock duration has elapsed")
    void shouldAutomaticallyUnlockWhenLockDurationExpires() throws Exception {
        // Set lockTime to 16 minutes in the past
        User user = userService.findByUsername(TEST_USER).orElseThrow();
        user.setFailedAttempts(5);
        user.setLockTime(Instant.now().minusSeconds(16 * 60));
        userService.saveUser(user);

        // Next login attempt with correct password succeeds and clears lock
        AuthRequest correctRequest = new AuthRequest(TEST_USER, TEST_PASS);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()));

        User unlockedUser = userService.findByUsername(TEST_USER).orElseThrow();
        assertEquals(0, unlockedUser.getFailedAttempts());
        assertNull(unlockedUser.getLockTime());
    }

    @Test
    @DisplayName("Unknown user login failure returns 401 without user enumeration leak")
    void shouldNotLeakUserExistenceForUnknownUser() throws Exception {
        AuthRequest unknownRequest = new AuthRequest("totally_unknown_user_123", "AnyPassword#123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid username or password")));
    }
}
