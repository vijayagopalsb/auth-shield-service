package com.example.jwtservicedemo.controller;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.dto.AuthResponse;
import com.example.jwtservicedemo.jwt.RsaJwtService;
import com.example.jwtservicedemo.model.User;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.example.jwtservicedemo.service.UserService;
import com.example.jwtservicedemo.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RsaJwtService rsaJwtService = new RsaJwtService();
    private final UserService userService = new UserService();

    @BeforeEach
    void setUp() {
        userService.initDefaultUsers();
        AuthController authController = new AuthController(userService, rsaJwtService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/auth/login - Success with pre-existing admin credentials")
    void shouldLoginSuccessfullyWithValidCredentials() throws Exception {
        AuthRequest request = new AuthRequest("admin", "admin");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    @DisplayName("POST /api/auth/login - Unauthorized with invalid password")
    void shouldFailLoginWithInvalidCredentials() throws Exception {
        AuthRequest request = new AuthRequest("admin", "WrongPassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", containsString("Invalid username or password")));
    }

    @Test
    @DisplayName("GET /api/auth/validate - Success when valid Bearer token is provided")
    void shouldValidateTokenSuccessfully() throws Exception {
        String token = rsaJwtService.generateToken("admin");

        mockMvc.perform(get("/api/auth/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.username", is("admin")));
    }

    @Test
    @DisplayName("GET /api/auth/validate - Bad request when Authorization header is missing")
    void shouldFailValidationWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/auth/validate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Missing or malformed Authorization header")));
    }

    @Test
    @DisplayName("POST /api/auth/register - Successfully register new user")
    void shouldRegisterNewUserInMemory() throws Exception {
        String rawJson = "{\"username\":\"new_engineer\",\"password\":\"NewPass#2026\",\"role\":\"ENGINEER\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is("new_engineer")))
                .andExpect(jsonPath("$.role", is("ENGINEER")));

        // Verify the newly registered user can now login
        AuthRequest loginRequest = new AuthRequest("new_engineer", "NewPass#2026");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("new_engineer")));
    }

    @Test
    @DisplayName("GET /api/auth/profile - Successfully retrieve user profile with password hidden")
    void shouldRetrieveProfileWithValidToken() throws Exception {
        String token = rsaJwtService.generateToken("admin");

        mockMvc.perform(get("/api/auth/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/auth/users - Retrieve all users with passwords hidden")
    void shouldRetrieveAllUsers() throws Exception {
        mockMvc.perform(get("/api/auth/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/auth/register - Reject privilege escalation when registering with ADMIN role")
    void shouldRejectRegistrationWithAdminPrivilege() throws Exception {
        String adminAttempt = "{\"username\":\"malicious_user\",\"password\":\"AdminAttempt#123\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminAttempt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("Self-registration with ADMIN privileges is prohibited")));
    }

    @Test
    @DisplayName("POST /api/auth/register - Fail validation when required fields are blank")
    void shouldFailValidationWhenRegisterWithBlankFields() throws Exception {
        String invalidPayload = "{\"username\":\"\",\"password\":\"\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.validationErrors.username", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.password", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/auth/register - Default role to USER when not specified")
    void shouldDefaultRoleToUserWhenNotSpecified() throws Exception {
        String noRolePayload = "{\"username\":\"standard_member\",\"password\":\"MemberPass#2026\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noRolePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is("standard_member")))
                .andExpect(jsonPath("$.role", is("USER")));
    }

    @Test
    @DisplayName("POST /api/auth/login - Token must be RS256 algorithm signed with RSA Private Key")
    void shouldGenerateRs256AlgorithmToken() throws Exception {
        AuthRequest request = new AuthRequest("admin", "admin");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        AuthResponse authResponse = objectMapper.readValue(responseJson, AuthResponse.class);
        String token = authResponse.getToken();

        // Decode JWT header
        String[] parts = token.split("\\.");
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        assertTrue(headerJson.contains("\"alg\":\"RS256\""), "JWT header must use RS256 algorithm: " + headerJson);
    }
}
