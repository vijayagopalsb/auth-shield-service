package com.example.jwtservicedemo.service;

import com.example.jwtservicedemo.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    private UserService userService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userService = new UserService(passwordEncoder);
        userService.initDefaultUsers();
    }

    @Test
    @DisplayName("Pre-seeded admin user must store BCrypt hashed password, never cleartext")
    void shouldStorePasswordsAsBCryptHashes() {
        Optional<User> admin = userService.findByUsername("admin");
        assertTrue(admin.isPresent());
        assertNotEquals("admin", admin.get().getPassword());
        assertTrue(admin.get().getPassword().startsWith("$2a$") || admin.get().getPassword().startsWith("$2b$"),
                "Password must be a BCrypt hash starting with $2a$ or $2b$");

        // Verify BCrypt matches raw password
        assertTrue(passwordEncoder.matches("admin", admin.get().getPassword()));
    }

    @Test
    @DisplayName("Should successfully authenticate admin using correct raw password")
    void shouldAuthenticateSuccessfully() {
        Optional<User> user = userService.authenticate("admin", "admin");
        assertTrue(user.isPresent());
        assertEquals("admin", user.get().getUsername());
        assertEquals("ADMIN", user.get().getRole());
    }

    @Test
    @DisplayName("Should reject authentication when given incorrect password")
    void shouldFailAuthenticationWithWrongPassword() {
        Optional<User> user = userService.authenticate("admin", "IncorrectPassword123");
        assertTrue(user.isEmpty());
    }

    @Test
    @DisplayName("Should reject authentication for non-existent username")
    void shouldFailAuthenticationForUnknownUser() {
        Optional<User> user = userService.authenticate("non_existent_user", "admin");
        assertTrue(user.isEmpty());
    }

    @Test
    @DisplayName("Registering new user must automatically hash the cleartext password")
    void shouldHashPasswordOnRegister() {
        User newUser = User.builder()
                .username("clara_lead")
                .password("ClaraSecret@2026")
                .role("LEAD")
                .build();

        User registered = userService.register(newUser);
        assertNotEquals("ClaraSecret@2026", registered.getPassword());
        assertTrue(passwordEncoder.matches("ClaraSecret@2026", registered.getPassword()));

        // Authenticate with new user
        Optional<User> authenticated = userService.authenticate("clara_lead", "ClaraSecret@2026");
        assertTrue(authenticated.isPresent());
    }

    @Test
    @DisplayName("loadUserByUsername must return UserDetails with BCrypt hash and ROLE_ authority")
    void shouldLoadUserDetailsForSpringSecurity() {
        UserDetails userDetails = userService.loadUserByUsername("admin");
        assertNotNull(userDetails);
        assertEquals("admin", userDetails.getUsername());
        assertTrue(passwordEncoder.matches("admin", userDetails.getPassword()));
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }
}
