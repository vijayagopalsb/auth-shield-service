package com.example.jwtservicedemo;

import com.example.jwtservicedemo.model.User;
import com.example.jwtservicedemo.repository.UserRepository;
import com.example.jwtservicedemo.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/jwt_service_db?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "server.ssl.enabled=false"
})
class MySqlIntegrationVerificationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Verify that MySQL database connects, creates users table, and contains pre-existing admin user")
    void testMySqlAdminUserPersistence() {
        Optional<User> admin = userRepository.findByUsername("admin");
        assertTrue(admin.isPresent(), "Admin user must be present in MySQL database");
        assertEquals("admin", admin.get().getUsername());
        assertEquals("ADMIN", admin.get().getRole());
        assertTrue(passwordEncoder.matches("admin", admin.get().getPassword()),
                "Admin user password must match 'admin' when checked with BCrypt");

        // Verify authentication via UserService
        Optional<User> authenticated = userService.authenticate("admin", "admin");
        assertTrue(authenticated.isPresent(), "Should authenticate admin successfully against MySQL");
    }
}
