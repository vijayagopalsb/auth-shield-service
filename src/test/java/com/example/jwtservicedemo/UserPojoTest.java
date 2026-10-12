package com.example.jwtservicedemo;

import com.example.jwtservicedemo.jwt.JwtService;
import com.example.jwtservicedemo.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserPojoTest {

    private final JwtService jwtService = new JwtService();

    @Test
    @DisplayName("Should create User POJO and set values using Lombok setters")
    void shouldSetUserValuesUsingSetters() {
        User user = new User();
        user.setUsername("alex_dev");
        user.setPassword("SecretPass@2026");
        user.setRole("DEVELOPER");

        assertEquals("alex_dev", user.getUsername());
        assertEquals("SecretPass@2026", user.getPassword());
        assertEquals("DEVELOPER", user.getRole());
    }

    @Test
    @DisplayName("Should create User POJO and set values using constructors")
    void shouldSetUserValuesUsingConstructors() {
        // 2-arg constructor
        User basicUser = new User("guest_user", "Guest@123");
        assertEquals("guest_user", basicUser.getUsername());
        assertEquals("Guest@123", basicUser.getPassword());
        assertEquals("USER", basicUser.getRole());

        // 3-arg constructor
        User adminUser = new User("sarah_admin", "AdminPass#999", "ADMIN");
        assertEquals("sarah_admin", adminUser.getUsername());
        assertEquals("AdminPass#999", adminUser.getPassword());
        assertEquals("ADMIN", adminUser.getRole());
    }

    @Test
    @DisplayName("Should create User POJO using Lombok Builder pattern")
    void shouldSetUserValuesUsingLombokBuilder() {
        User builtUser = User.builder()
                .username("david_lead")
                .password("Lead@Secure2026")
                .role("TECH_LEAD")
                .build();

        assertEquals("david_lead", builtUser.getUsername());
        assertEquals("Lead@Secure2026", builtUser.getPassword());
        assertEquals("TECH_LEAD", builtUser.getRole());

        // Password should be excluded from toString for security
        assertFalse(builtUser.toString().contains("Lead@Secure2026"), "Sensitive password must not be exposed in toString()");
    }

    @Test
    @DisplayName("Should generate and validate JWT token for POJO User without any DB")
    void shouldGenerateAndValidateJwtForPojoUser() {
        // 1. Create User POJO via Lombok builder
        User user = User.builder()
                .username("john_doe")
                .password("MySecurePassword#1")
                .role("ROLE_MANAGER")
                .build();

        // 2. Add custom claims from POJO user
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());

        // 3. Generate token
        String token = jwtService.generateToken(claims, user.getUsername());
        assertNotNull(token);
        assertFalse(token.isBlank());

        // 4. Extract and validate
        String extractedUsername = jwtService.extractUsername(token);
        assertEquals(user.getUsername(), extractedUsername);
        assertTrue(jwtService.isTokenValid(token, user.getUsername()));
    }
}
