package com.example.jwtservicedemo.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist service with automatic expired entry eviction.
 * Used for immediate revocation of JWT access tokens when a user logs out
 * or when an active session is terminated.
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final RsaJwtService rsaJwtService;
    private final Map<String, Instant> blacklist = new ConcurrentHashMap<>();

    public TokenBlacklistService(RsaJwtService rsaJwtService) {
        this.rsaJwtService = rsaJwtService;
    }

    /**
     * Blacklists an access token until its natural expiration time.
     */
    public void blacklistToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        try {
            Date expiration = rsaJwtService.extractExpiration(token);
            blacklist.put(token, expiration.toInstant());
            log.info("Access token blacklisted until {}", expiration);
        } catch (Exception ex) {
            // Fallback: blacklist for standard 15 minutes if expiration parsing fails
            Instant fallbackExpiry = Instant.now().plusSeconds(15 * 60);
            blacklist.put(token, fallbackExpiry);
            log.warn("Failed to extract token expiration, using 15-minute fallback: {}", ex.getMessage());
        }
    }

    /**
     * Checks if a token is in the blacklist. Automatically purges expired entries.
     */
    public boolean isBlacklisted(String token) {
        if (token == null) {
            return false;
        }

        Instant expiry = blacklist.get(token);
        if (expiry == null) {
            return false;
        }

        if (expiry.isBefore(Instant.now())) {
            blacklist.remove(token); // Lazy cleanup of expired entry
            return false;
        }

        return true;
    }

    /**
     * Purges all expired tokens from memory.
     */
    public void cleanExpiredTokens() {
        Instant now = Instant.now();
        blacklist.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    /**
     * Clears all blacklisted tokens (useful for test reset).
     */
    public void clear() {
        blacklist.clear();
    }
}
