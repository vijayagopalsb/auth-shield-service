package com.example.jwtservicedemo.service;

import com.example.jwtservicedemo.audit.SecurityAuditEvent;
import com.example.jwtservicedemo.model.RefreshToken;
import com.example.jwtservicedemo.model.User;
import com.example.jwtservicedemo.repository.RefreshTokenRepository;
import com.example.jwtservicedemo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing Refresh Tokens with automatic Rotation and Reuse Detection.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    // Default: 7 days in milliseconds (7 * 24 * 60 * 60 * 1000)
    private final long refreshExpirationMs;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.refreshExpirationMs = refreshExpirationMs;
        this.eventPublisher = eventPublisher;
    }

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            long refreshExpirationMs) {
        this(refreshTokenRepository, userRepository, refreshExpirationMs, null);
    }

    /**
     * Issues a new RefreshToken for an authenticated user.
     */
    @Transactional
    public RefreshToken createRefreshToken(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(refreshExpirationMs))
                .revoked(false)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    /**
     * Finds a token by its token string.
     */
    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    /**
     * Performs Refresh Token Rotation (RTR) with Automatic Reuse Detection.
     *
     * If a revoked token is presented, this indicates a replay attack (token theft).
     * In response, ALL refresh tokens for the victim user are immediately revoked,
     * forcing a complete re-authentication.
     *
     * If valid, the current token is revoked, a brand new RefreshToken is issued,
     * and the lineage is recorded via replacedByToken.
     */
    @Transactional(noRollbackFor = SecurityException.class)
    public RefreshToken rotateRefreshToken(String requestTokenString) {
        RefreshToken token = refreshTokenRepository.findByToken(requestTokenString)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        User user = token.getUser();

        // 1. REUSE DETECTION: An already revoked token was used!
        if (token.isRevoked()) {
            log.error("SECURITY ALERT: Compromised refresh token reuse detected for user '{}'! Revoking all sessions.", user.getUsername());
            refreshTokenRepository.revokeAllByUser(user);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new SecurityAuditEvent(
                        this,
                        "TOKEN_REUSE_BREACH",
                        user.getUsername(),
                        "ALERT",
                        "Compromised refresh token reuse detected! All active sessions revoked."
                ));
            }
            throw new SecurityException("Compromised token reuse detected! All active sessions have been invalidated for security.");
        }

        // 2. EXPIRATION CHECK
        if (token.getExpiryDate().isBefore(Instant.now())) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new IllegalArgumentException("Refresh token has expired. Please sign in again.");
        }

        // 3. ROTATION: Revoke old token and issue a fresh new token
        token.setRevoked(true);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(refreshExpirationMs))
                .revoked(false)
                .build();

        RefreshToken savedNewToken = refreshTokenRepository.save(newRefreshToken);

        // Record lineage
        token.setReplacedByToken(savedNewToken.getToken());
        refreshTokenRepository.save(token);

        log.info("Refresh token rotated successfully for user '{}'.", user.getUsername());
        return savedNewToken;
    }

    /**
     * Revokes a specific refresh token (used on logout).
     */
    @Transactional
    public void revokeRefreshToken(String tokenString) {
        refreshTokenRepository.findByToken(tokenString).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            log.info("Refresh token revoked for user '{}'.", token.getUser().getUsername());
        });
    }

    /**
     * Revokes all refresh tokens belonging to a user.
     */
    @Transactional
    public void revokeAllUserTokens(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            refreshTokenRepository.revokeAllByUser(user);
            log.info("All refresh tokens revoked for user '{}'.", username);
        });
    }
}
