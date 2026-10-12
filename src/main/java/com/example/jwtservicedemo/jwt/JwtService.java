package com.example.jwtservicedemo.jwt;

import io.jsonwebtoken.Claims;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.function.Function;

/**
 * Service providing asymmetric RSA (RS256) JWT generation and validation using PEM keys.
 * Delegating to RsaJwtService for centralized asymmetric cryptographic operations.
 */
@Service
@Primary
public class JwtService {

    private final RsaJwtService rsaJwtService;

    public JwtService(RsaJwtService rsaJwtService) {
        this.rsaJwtService = rsaJwtService;
    }

    public JwtService() {
        this(new RsaJwtService());
    }

    // Generates a token with just the username
    public String generateToken(String username) {
        return rsaJwtService.generateToken(username);
    }

    // Generates a token with custom claims (username first overload)
    public String generateToken(String username, Map<String, Object> extraClaims) {
        return rsaJwtService.generateToken(username, extraClaims);
    }

    // Generates a token with custom claims using RSA Private Key (RS256)
    public String generateToken(Map<String, Object> extraClaims, String username) {
        return rsaJwtService.generateToken(extraClaims, username);
    }

    // Extracts the username (Subject) from the token using RSA Public Key
    public String extractUsername(String token) {
        return rsaJwtService.extractUsername(token);
    }

    // Generic method to extract a specific claim using RSA Public Key
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return rsaJwtService.extractClaim(token, claimsResolver);
    }

    // Validates if the token matches the username and is not expired
    public boolean isTokenValid(String token, String username) {
        return rsaJwtService.isTokenValid(token, username);
    }

    // Validates if the token matches UserDetails and is not expired
    public boolean isTokenValid(String token, UserDetails userDetails) {
        return rsaJwtService.isTokenValid(token, userDetails);
    }

    // Extracts role claim from JWT payload
    public String extractRole(String token) {
        return rsaJwtService.extractRole(token);
    }

    // Checks whether the token has expired
    public boolean isTokenExpired(String token) {
        return rsaJwtService.isTokenExpired(token);
    }

    // Extracts the expiration date claim
    public Date extractExpiration(String token) {
        return rsaJwtService.extractExpiration(token);
    }

    public RsaJwtService getRsaJwtService() {
        return rsaJwtService;
    }
}