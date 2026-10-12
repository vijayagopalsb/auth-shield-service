package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.dto.AuthResponse;
import com.example.jwtservicedemo.dto.MfaLoginRequest;
import com.example.jwtservicedemo.dto.MfaSetupResponse;
import com.example.jwtservicedemo.dto.MfaVerifyRequest;
import com.example.jwtservicedemo.mfa.TotpService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
public class MfaTotpIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserService userService;

    @Autowired
    private TotpService totpService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MFA_USER = "mfa_test_user";
    private static final String MFA_PASS = "MfaTest#Pass123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Reset user state
        if (userService.findByUsername(MFA_USER).isEmpty()) {
            userService.register(User.builder()
                    .username(MFA_USER)
                    .password(MFA_PASS)
                    .role("USER")
                    .build());
        } else {
            userService.unlockAccount(MFA_USER);
            userService.disableMfa(MFA_USER);
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
    @DisplayName("POST /api/auth/mfa/setup: Generates Base32 secret and QR Code image")
    void shouldGenerateMfaSetupKeysAndQrCode() throws Exception {
        String token = obtainToken(MFA_USER, MFA_PASS);

        MvcResult result = mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret", notNullValue()))
                .andExpect(jsonPath("$.manualEntryKey", notNullValue()))
                .andExpect(jsonPath("$.otpAuthUri", containsString("otpauth://totp/")))
                .andExpect(jsonPath("$.qrCodeDataUrl", startsWith("data:image/png;base64,")))
                .andReturn();

        MfaSetupResponse setupResp = objectMapper.readValue(result.getResponse().getContentAsString(), MfaSetupResponse.class);
        assertEquals(32, setupResp.getSecret().length(), "Base32 secret key must be 32 characters (160 bits)");
    }

    @Test
    @DisplayName("POST /api/auth/mfa/enable: Activates MFA when valid 6-digit TOTP code is provided")
    void shouldEnableMfaWithValidTotpCode() throws Exception {
        String token = obtainToken(MFA_USER, MFA_PASS);

        // 1. Initiate setup
        MvcResult setupResult = mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        MfaSetupResponse setupResp = objectMapper.readValue(setupResult.getResponse().getContentAsString(), MfaSetupResponse.class);

        // 2. Generate current 6-digit code
        String currentCode = totpService.generateCurrentTotp(setupResp.getSecret());

        // 3. Confirm & Enable
        MfaVerifyRequest verifyReq = new MfaVerifyRequest(currentCode);
        mockMvc.perform(post("/api/auth/mfa/enable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("MFA enabled successfully")));

        // Verify DB entity
        User updated = userService.findByUsername(MFA_USER).orElseThrow();
        assertTrue(updated.isMfaEnabled(), "User should have mfaEnabled = true");
        assertNotNull(updated.getMfaSecret(), "User must retain secret in database");
    }

    @Test
    @DisplayName("POST /api/auth/mfa/enable: Rejects invalid 6-digit TOTP code")
    void shouldRejectMfaEnableWithInvalidCode() throws Exception {
        String token = obtainToken(MFA_USER, MFA_PASS);

        // 1. Initiate setup
        mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 2. Attempt enable with wrong code
        MfaVerifyRequest verifyReq = new MfaVerifyRequest("000000");
        mockMvc.perform(post("/api/auth/mfa/enable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid 6-digit MFA code")));

        User user = userService.findByUsername(MFA_USER).orElseThrow();
        assertFalse(user.isMfaEnabled(), "User must not have MFA enabled on invalid code");
    }

    @Test
    @DisplayName("Full Login Flow: Step 1 returns mfaRequired & tempToken; Step 2 returns full access token")
    void shouldPerformFullTwoStepMfaAuthentication() throws Exception {
        // Step A: Setup & Enable MFA for user
        String setupToken = obtainToken(MFA_USER, MFA_PASS);
        MvcResult setupResult = mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + setupToken))
                .andReturn();
        MfaSetupResponse setupResp = objectMapper.readValue(setupResult.getResponse().getContentAsString(), MfaSetupResponse.class);
        String currentCode = totpService.generateCurrentTotp(setupResp.getSecret());
        mockMvc.perform(post("/api/auth/mfa/enable")
                .header("Authorization", "Bearer " + setupToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MfaVerifyRequest(currentCode))));

        // Step B: User initiates primary login (Username + Password)
        AuthRequest loginReq = new AuthRequest(MFA_USER, MFA_PASS);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired", is(true)))
                .andExpect(jsonPath("$.tempToken", notNullValue()))
                .andExpect(jsonPath("$.token", nullValue())) // Full access token NOT yet issued
                .andReturn();

        AuthResponse loginResp = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        String tempToken = loginResp.getTempToken();

        // Step C: Verify with wrong code fails
        MfaLoginRequest wrongMfa = new MfaLoginRequest(tempToken, "111111");
        mockMvc.perform(post("/api/auth/mfa/verify-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongMfa)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("Invalid 6-digit MFA code")));

        // Step D: Verify with valid TOTP code succeeds and issues full RS256 token
        String validTotp = totpService.generateCurrentTotp(setupResp.getSecret());
        MfaLoginRequest validMfa = new MfaLoginRequest(tempToken, validTotp);
        mockMvc.perform(post("/api/auth/mfa/verify-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validMfa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.username", is(MFA_USER)))
                .andExpect(jsonPath("$.message", containsString("successful")));
    }

    @Test
    @DisplayName("Security Guard: Temporary MFA tokens cannot access protected business APIs")
    void shouldBlockMfaTempTokenFromGeneralApiAccess() throws Exception {
        // Enable MFA
        String setupToken = obtainToken(MFA_USER, MFA_PASS);
        MvcResult setupResult = mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + setupToken))
                .andReturn();
        MfaSetupResponse setupResp = objectMapper.readValue(setupResult.getResponse().getContentAsString(), MfaSetupResponse.class);
        String code = totpService.generateCurrentTotp(setupResp.getSecret());
        mockMvc.perform(post("/api/auth/mfa/enable")
                .header("Authorization", "Bearer " + setupToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MfaVerifyRequest(code))));

        // Primary login to obtain tempToken
        AuthRequest loginReq = new AuthRequest(MFA_USER, MFA_PASS);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andReturn();
        AuthResponse loginResp = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        String tempToken = loginResp.getTempToken();

        // Attempt to access protected endpoint using tempToken -> Must be rejected (401 or 403)
        mockMvc.perform(get("/api/demo/user-area")
                        .header("Authorization", "Bearer " + tempToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/mfa/disable: Disables MFA when valid 6-digit code is provided")
    void shouldDisableMfaSuccessfully() throws Exception {
        // 1. Enable MFA
        String token = obtainToken(MFA_USER, MFA_PASS);
        MvcResult setupResult = mockMvc.perform(post("/api/auth/mfa/setup")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        MfaSetupResponse setupResp = objectMapper.readValue(setupResult.getResponse().getContentAsString(), MfaSetupResponse.class);
        String code = totpService.generateCurrentTotp(setupResp.getSecret());
        mockMvc.perform(post("/api/auth/mfa/enable")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MfaVerifyRequest(code))));

        // 2. Disable MFA with current code
        String disableCode = totpService.generateCurrentTotp(setupResp.getSecret());
        mockMvc.perform(post("/api/auth/mfa/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaVerifyRequest(disableCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("MFA disabled successfully")));

        User user = userService.findByUsername(MFA_USER).orElseThrow();
        assertFalse(user.isMfaEnabled());
    }
}
