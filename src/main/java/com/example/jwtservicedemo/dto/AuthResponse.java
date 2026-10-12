package com.example.jwtservicedemo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;

    @Builder.Default
    private String tokenType = "Bearer";

    private String username;
    private String role;
    private String refreshToken;
    private long expiresInMs;
    private String message;

    @Builder.Default
    private boolean mfaRequired = false;

    private String tempToken;
}
