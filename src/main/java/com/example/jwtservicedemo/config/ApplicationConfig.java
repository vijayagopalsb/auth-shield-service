package com.example.jwtservicedemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * General application infrastructure configuration.
 * Declares PasswordEncoder to isolate security crypto beans from SecurityFilterChain lifecycle.
 */
@Configuration
public class ApplicationConfig {

    /**
     * Standard BCrypt password encoder with default strength (10 rounds).
     * Provides cryptographic salt and constant-time password hash verification.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
