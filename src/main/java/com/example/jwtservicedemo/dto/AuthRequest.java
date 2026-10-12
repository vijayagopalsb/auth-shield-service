package com.example.jwtservicedemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an authentication / login request.
 * Contains credentials (username, password) and an optional session role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 4, max = 100, message = "Password must be at least 4 characters")
    private String password;

    // Optional requested role for the session (must be owned or inherited by the user)
    private String role;

    // 2-arg convenience constructor for backward compatibility
    public AuthRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
