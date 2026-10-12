package com.example.jwtservicedemo.repository;

import com.example.jwtservicedemo.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Should save and find user by username in database")
    void shouldSaveAndFindByUsername() {
        User user = User.builder()
                .username("db_admin_test")
                .password("$2a$10$hashedpasswordexample")
                .role("ADMIN")
                .build();

        userRepository.save(user);

        Optional<User> found = userRepository.findByUsername("db_admin_test");
        assertTrue(found.isPresent());
        assertEquals("db_admin_test", found.get().getUsername());
        assertEquals("ADMIN", found.get().getRole());
        assertTrue(userRepository.existsByUsername("db_admin_test"));
    }

    @Test
    @DisplayName("Should return false when checking existence of unknown user")
    void shouldReturnFalseForNonExistentUser() {
        assertFalse(userRepository.existsByUsername("non_existent_unique_12345"));
        assertTrue(userRepository.findByUsername("non_existent_unique_12345").isEmpty());
    }
}
