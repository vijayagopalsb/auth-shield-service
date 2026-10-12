package com.example.jwtservicedemo;

import com.example.jwtservicedemo.dto.AuthRequest;
import com.example.jwtservicedemo.dto.RegisterRequest;
import com.example.jwtservicedemo.dto.RoleUpdateRequest;
import com.example.jwtservicedemo.jwt.RsaJwtService;
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

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class HierarchicalRoleSecurityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RsaJwtService rsaJwtService;

    @Autowired
    private UserService userService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        if (userService.findByUsername("test_user").isEmpty()) {
            userService.register(User.builder().username("test_user").password("TestPass#123").role("USER").build());
        }
        if (userService.findByUsername("test_manager").isEmpty()) {
            userService.register(User.builder().username("test_manager").password("TestPass#123").role("MANAGER").build());
        }
        if (userService.findByUsername("test_admin").isEmpty()) {
            userService.register(User.builder().username("test_admin").password("TestPass#123").role("ADMIN").build());
        }
    }

    // -------------------------------------------------------------
    // Part 1: Registration Defaults & Privilege Escalation Checks
    // -------------------------------------------------------------

    @Test
    @DisplayName("Self-registration assigns USER role by default")
    void shouldRegisterWithDefaultRoleUser() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .username("standard_alice")
                .password("AlicePass#2026")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is("standard_alice")))
                .andExpect(jsonPath("$.role", is("USER")));
    }

    @Test
    @DisplayName("Self-registration rejects attempts to claim ADMIN or MANAGER role")
    void shouldRejectSelfRegistrationWithManagerOrAdmin() throws Exception {
        RegisterRequest adminAttempt = RegisterRequest.builder()
                .username("sneaky_admin")
                .password("SneakyPass#123")
                .role("ADMIN")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminAttempt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Self-registration with ADMIN privileges is prohibited")));

        RegisterRequest managerAttempt = RegisterRequest.builder()
                .username("sneaky_manager")
                .password("SneakyPass#123")
                .role("MANAGER")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(managerAttempt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Self-registration with MANAGER privileges is prohibited")));
    }

    // -------------------------------------------------------------
    // Part 2: Login with Optional Role & Hierarchy Verification
    // -------------------------------------------------------------

    @Test
    @DisplayName("Login without role parameter defaults to database role")
    void shouldLoginWithDefaultDbRoleWhenRoleOmitted() throws Exception {
        AuthRequest request = new AuthRequest("admin", "admin");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    @DisplayName("Admin can optionally log in assuming inherited lower role (MANAGER or USER)")
    void shouldAllowAdminToAssumeLowerRoleInSession() throws Exception {
        AuthRequest requestAsManager = AuthRequest.builder()
                .username("admin")
                .password("admin")
                .role("MANAGER")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestAsManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("MANAGER")));

        AuthRequest requestAsUser = AuthRequest.builder()
                .username("admin")
                .password("admin")
                .role("USER")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestAsUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("USER")));
    }

    @Test
    @DisplayName("Regular user cannot log in assuming a higher role (ADMIN or MANAGER)")
    void shouldRejectRegularUserAssumingHigherRole() throws Exception {
        // Register standard user
        User regular = User.builder()
                .username("bob_user")
                .password("BobPass#2026")
                .role("USER")
                .build();
        userService.register(regular);

        AuthRequest escalateAttempt = AuthRequest.builder()
                .username("bob_user")
                .password("BobPass#2026")
                .role("ADMIN")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(escalateAttempt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("cannot assume requested role 'ADMIN'")));
    }

    // -------------------------------------------------------------
    // Part 3: Admin Role Assignment (PUT /api/auth/users/{username}/role)
    // -------------------------------------------------------------

    @Test
    @DisplayName("Main admin can assign or promote a user to MANAGER or ADMIN")
    void shouldAllowAdminToPromoteUser() throws Exception {
        // Seed user
        User target = User.builder()
                .username("carol_member")
                .password("CarolPass#2026")
                .role("USER")
                .build();
        userService.register(target);

        // Generate token for admin
        String adminToken = rsaJwtService.generateToken(Map.of("role", "ADMIN"), "admin");

        RoleUpdateRequest updateRequest = new RoleUpdateRequest("MANAGER");

        mockMvc.perform(put("/api/auth/users/carol_member/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("carol_member")))
                .andExpect(jsonPath("$.role", is("MANAGER")));

        // Verify user in service now has MANAGER role
        User updated = userService.findByUsername("carol_member").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("MANAGER", updated.getRole());
    }

    @Test
    @DisplayName("Non-admin user cannot promote roles (403 Forbidden)")
    void shouldRejectNonAdminRolePromotion() throws Exception {
        String userToken = rsaJwtService.generateToken(Map.of("role", "USER"), "standard_alice");
        RoleUpdateRequest updateRequest = new RoleUpdateRequest("ADMIN");

        mockMvc.perform(put("/api/auth/users/standard_alice/role")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------
    // Part 4: Role Hierarchy Endpoints Access Verification
    // ADMIN -> implies MANAGER -> implies USER
    // -------------------------------------------------------------

    @Test
    @DisplayName("ROLE_USER can only access user-area; rejected on manager-area and admin-area")
    void testUserRoleAccess() throws Exception {
        String userToken = rsaJwtService.generateToken(Map.of("role", "USER"), "test_user");

        // 1. User area -> 200 OK
        mockMvc.perform(get("/api/demo/user-area")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredRole", is("ROLE_USER")));

        // 2. Manager area -> 403 Forbidden
        mockMvc.perform(get("/api/demo/manager-area")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 3. Admin area -> 403 Forbidden
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_MANAGER can access user-area (inherited) and manager-area; rejected on admin-area")
    void testManagerRoleAccess() throws Exception {
        String managerToken = rsaJwtService.generateToken(Map.of("role", "MANAGER"), "test_manager");

        // 1. User area (inherited) -> 200 OK
        mockMvc.perform(get("/api/demo/user-area")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        // 2. Manager area -> 200 OK
        mockMvc.perform(get("/api/demo/manager-area")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredRole", is("ROLE_MANAGER")));

        // 3. Admin area -> 403 Forbidden
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_ADMIN can access all 3 areas (user-area, manager-area, admin-area)")
    void testAdminRoleAccess() throws Exception {
        String adminToken = rsaJwtService.generateToken(Map.of("role", "ADMIN"), "test_admin");

        // 1. User area (inherited) -> 200 OK
        mockMvc.perform(get("/api/demo/user-area")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 2. Manager area (inherited) -> 200 OK
        mockMvc.perform(get("/api/demo/manager-area")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 3. Admin area -> 200 OK
        mockMvc.perform(get("/api/demo/admin-area")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredRole", is("ROLE_ADMIN")));
    }
}
