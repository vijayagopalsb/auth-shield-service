package com.example.jwtservicedemo.controller;

import com.example.jwtservicedemo.audit.SecurityAuditEvent;
import com.example.jwtservicedemo.dto.*;
import com.example.jwtservicedemo.jwt.RsaJwtService;
import com.example.jwtservicedemo.jwt.TokenBlacklistService;
import com.example.jwtservicedemo.mfa.TotpService;
import com.example.jwtservicedemo.model.RefreshToken;
import com.example.jwtservicedemo.model.SecurityAuditLog;
import com.example.jwtservicedemo.model.User;
import com.example.jwtservicedemo.repository.SecurityAuditLogRepository;
import com.example.jwtservicedemo.service.RefreshTokenService;
import com.example.jwtservicedemo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST Controller for JWT authentication, MFA (TOTP / RFC 6238), and token management using asymmetric RSA (RS256).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final RsaJwtService rsaJwtService;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityAuditLogRepository securityAuditLogRepository;
    private final TotpService totpService;

    public AuthController(UserService userService, RsaJwtService rsaJwtService) {
        this(userService, rsaJwtService, null, new TokenBlacklistService(rsaJwtService), null, null, new TotpService());
    }

    public AuthController(UserService userService,
                          RsaJwtService rsaJwtService,
                          RefreshTokenService refreshTokenService,
                          TokenBlacklistService tokenBlacklistService) {
        this(userService, rsaJwtService, refreshTokenService, tokenBlacklistService, null, null, new TotpService());
    }

    @Autowired
    public AuthController(UserService userService,
                          RsaJwtService rsaJwtService,
                          @Autowired(required = false) RefreshTokenService refreshTokenService,
                          @Autowired(required = false) TokenBlacklistService tokenBlacklistService,
                          @Autowired(required = false) ApplicationEventPublisher eventPublisher,
                          @Autowired(required = false) SecurityAuditLogRepository securityAuditLogRepository,
                          @Autowired(required = false) TotpService totpService) {
        this.userService = userService;
        this.rsaJwtService = rsaJwtService;
        this.refreshTokenService = refreshTokenService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.eventPublisher = eventPublisher;
        this.securityAuditLogRepository = securityAuditLogRepository;
        this.totpService = totpService != null ? totpService : new TotpService();
    }

    private void publishSecurityEvent(String eventType, String username, String status, String details) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SecurityAuditEvent(this, eventType, username, status, details));
        }
    }

    /**
     * Authenticate user with in-memory POJO credentials and issue a JWT token.
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest request) {
        Optional<User> optionalUser = userService.authenticate(request.getUsername(), request.getPassword());

        if (optionalUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Invalid username or password"
            ));
        }

        User user = optionalUser.get();
        String activeRole = user.getRole();

        // Optional Role Handling: If client requested a specific role, verify they can assume it under the hierarchy
        if (request.getRole() != null && !request.getRole().isBlank()) {
            String requestedRole = request.getRole().trim().toUpperCase().replace("ROLE_", "");
            if (!userService.canAssumeRole(user.getRole(), requestedRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "status", HttpStatus.FORBIDDEN.value(),
                        "error", "Forbidden",
                        "message", "User '" + user.getUsername() + "' with role '" + user.getRole()
                                + "' cannot assume requested role '" + requestedRole + "'"
                ));
            }
            activeRole = requestedRole;
        }

        // If Multi-Factor Authentication is enabled on this account, interrupt login and require Step 2 TOTP code
        if (user.isMfaEnabled()) {
            String tempToken = rsaJwtService.generateMfaTempToken(user.getUsername(), activeRole);
            return ResponseEntity.ok(AuthResponse.builder()
                    .mfaRequired(true)
                    .tempToken(tempToken)
                    .username(user.getUsername())
                    .message("MFA verification required. Please provide 6-digit TOTP code.")
                    .build());
        }

        // Add user roles and custom claims into the JWT payload
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", activeRole);

        // Generate JWT access token
        String token = rsaJwtService.generateToken(claims, user.getUsername());

        // Generate Refresh Token if service is available
        String refreshTokenStr = null;
        if (refreshTokenService != null) {
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getUsername());
            refreshTokenStr = refreshToken.getToken();
        }

        AuthResponse response = AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .role(activeRole)
                .refreshToken(refreshTokenStr)
                .expiresInMs(1000 * 60 * 15) // 15 minutes
                .message("Authentication successful")
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Refreshes an expired access token using Refresh Token Rotation (RTR).
     * If an already-rotated token is received (reuse attack), all user sessions are immediately revoked.
     * POST /api/auth/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        if (refreshTokenService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "message", "RefreshToken service is not configured"
            ));
        }

        try {
            RefreshToken rotatedToken = refreshTokenService.rotateRefreshToken(request.getRefreshToken());
            User user = rotatedToken.getUser();

            // Generate fresh RS256 Access Token
            Map<String, Object> claims = new HashMap<>();
            claims.put("role", user.getRole());
            String newAccessToken = rsaJwtService.generateToken(claims, user.getUsername());

            AuthResponse response = AuthResponse.builder()
                    .token(newAccessToken)
                    .tokenType("Bearer")
                    .username(user.getUsername())
                    .role(user.getRole())
                    .refreshToken(rotatedToken.getToken())
                    .expiresInMs(1000 * 60 * 15) // 15 minutes
                    .message("Token refreshed successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (SecurityException ex) {
            // Compromised token reuse detected -> Forbidden
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", HttpStatus.FORBIDDEN.value(),
                    "error", "Forbidden",
                    "message", ex.getMessage()
            ));
        } catch (IllegalArgumentException ex) {
            // Expired or invalid token -> Unauthorized
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", ex.getMessage()
            ));
        }
    }

    /**
     * Terminate user session.
     * Immediately blacklists the Access Token and revokes the Refresh Token.
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) RefreshTokenRequest request) {

        // 1. Blacklist the incoming Access Token if present (immediate stateless token revocation)
        if (authHeader != null && authHeader.startsWith("Bearer ") && tokenBlacklistService != null) {
            String jwt = authHeader.substring(7);
            tokenBlacklistService.blacklistToken(jwt);
        }

        // 2. Revoke the Refresh Token if provided in body
        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank() && refreshTokenService != null) {
            refreshTokenService.revokeRefreshToken(request.getRefreshToken());
        }

        // 3. Determine username for audit trail and revoke all user refresh tokens if authenticated
        String loggedOutUser = "anonymous";
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            loggedOutUser = auth.getName();
            if (refreshTokenService != null) {
                refreshTokenService.revokeAllUserTokens(loggedOutUser);
            }
        } else if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                loggedOutUser = rsaJwtService.extractUsername(authHeader.substring(7));
            } catch (Exception ignored) {
            }
        } else if (request != null && request.getRefreshToken() != null && refreshTokenService != null) {
            loggedOutUser = refreshTokenService.findByToken(request.getRefreshToken())
                    .map(token -> token.getUser().getUsername())
                    .orElse("anonymous");
        }

        // 4. Publish security audit event
        publishSecurityEvent("LOGOUT", loggedOutUser, "SUCCESS", "User session terminated, access token blacklisted");

        return ResponseEntity.ok(Map.of(
                "status", HttpStatus.OK.value(),
                "message", "Successfully logged out. Access token blacklisted and refresh token revoked."
        ));
    }

    /**
     * Validate an incoming JWT token from the Authorization header.
     * GET /api/auth/validate
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Missing or malformed Authorization header. Expected format: 'Bearer <token>'"
            ));
        }

        String token = authHeader.substring(7);

        try {
            String username = rsaJwtService.extractUsername(token);
            boolean isValid = rsaJwtService.isTokenValid(token, username);

            if (isValid) {
                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "username", username,
                        "message", "Token is valid and active"
                ));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "valid", false,
                        "message", "Token is expired or invalid"
                ));
            }
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "valid", false,
                    "error", ex.getClass().getSimpleName(),
                    "message", ex.getMessage()
            ));
        }
    }

    /**
     * Retrieve the authenticated user's profile from their JWT token.
     * GET /api/auth/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = null;

        // 1. Check SecurityContextHolder populated by JwtAuthenticationFilter
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            username = auth.getName();
        }

        // 2. Fallback to extracting from Authorization header (supports standalone setup tests)
        if (username == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String extracted = rsaJwtService.extractUsername(token);
                if (rsaJwtService.isTokenValid(token, extracted)) {
                    username = extracted;
                }
            } catch (Exception ignored) {
            }
        }

        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "Unauthorized",
                    "message", "Valid Bearer token is required"
            ));
        }

        Optional<User> optionalUser = userService.findByUsername(username);
        if (optionalUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }

        return ResponseEntity.ok(optionalUser.get());
    }

    /**
     * Register a new POJO user in memory (no database).
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        // Prevent Privilege Escalation: Disallow self-registration with ADMIN or MANAGER privileges
        String requestedRole = request.getRole();
        if (requestedRole != null && (requestedRole.equalsIgnoreCase("ADMIN") || requestedRole.equalsIgnoreCase("ROLE_ADMIN"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", HttpStatus.FORBIDDEN.value(),
                    "error", "Forbidden",
                    "message", "Privilege escalation detected: Self-registration with ADMIN privileges is prohibited"
            ));
        }
        if (requestedRole != null && (requestedRole.equalsIgnoreCase("MANAGER") || requestedRole.equalsIgnoreCase("ROLE_MANAGER"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", HttpStatus.FORBIDDEN.value(),
                    "error", "Forbidden",
                    "message", "Privilege escalation detected: Self-registration with MANAGER privileges is prohibited"
            ));
        }

        if (userService.findByUsername(request.getUsername()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", HttpStatus.CONFLICT.value(),
                    "error", "Conflict",
                    "message", "User already exists: " + request.getUsername()
            ));
        }

        // Newly registered users are assigned USER role by default
        String assignedRole = (requestedRole != null && !requestedRole.isBlank()) ? requestedRole.toUpperCase() : "USER";
        User userToSave = User.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .role(assignedRole)
                .build();

        User savedUser = userService.register(userToSave);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "User registered successfully",
                "username", savedUser.getUsername(),
                "role", savedUser.getRole()
        ));
    }

    /**
     * Admin-only endpoint to assign or promote user roles.
     * PUT /api/auth/users/{username}/role
     */
    @PutMapping("/users/{username}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateUserRole(@PathVariable String username, @Valid @RequestBody RoleUpdateRequest request) {
        if (userService.findByUsername(username).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", HttpStatus.NOT_FOUND.value(),
                    "error", "Not Found",
                    "message", "User not found: " + username
            ));
        }

        User updatedUser = userService.updateRole(username, request.getRole());
        publishSecurityEvent("ROLE_UPDATED", username, "SUCCESS", "Role updated to " + updatedUser.getRole());
        return ResponseEntity.ok(Map.of(
                "message", "User role updated successfully",
                "username", updatedUser.getUsername(),
                "role", updatedUser.getRole()
        ));
    }

    /**
     * Admin-only endpoint to view security audit event logs for compliance and monitoring.
     * GET /api/auth/audit-logs
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SecurityAuditLog>> getAuditLogs() {
        if (securityAuditLogRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(securityAuditLogRepository.findTop50ByOrderByTimestampDesc());
    }

    /**
     * Initiates MFA enrollment by generating a Base32 secret and QR code.
     * POST /api/auth/mfa/setup
     */
    @PostMapping("/mfa/setup")
    public ResponseEntity<?> setupMfa(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = resolveAuthenticatedUsername(authHeader);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Authentication required to configure MFA"
            ));
        }

        String secret = totpService.generateSecretKey();
        userService.setMfaSecret(username, secret);

        String otpAuthUri = totpService.getOtpAuthUri(username, secret);
        String qrCodeDataUrl = totpService.generateQrCodeDataUrl(otpAuthUri, 250, 250);

        publishSecurityEvent("MFA_SETUP", username, "SUCCESS", "Initiated MFA enrollment");

        return ResponseEntity.ok(MfaSetupResponse.builder()
                .secret(secret)
                .manualEntryKey(secret)
                .otpAuthUri(otpAuthUri)
                .qrCodeDataUrl(qrCodeDataUrl)
                .message("Scan QR code in Google Authenticator or enter manual key, then call /api/auth/mfa/enable with 6-digit code")
                .build());
    }

    /**
     * Confirms and activates MFA using initial 6-digit TOTP code.
     * POST /api/auth/mfa/enable
     */
    @PostMapping("/mfa/enable")
    public ResponseEntity<?> enableMfa(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody MfaVerifyRequest request) {

        String username = resolveAuthenticatedUsername(authHeader);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Authentication required"
            ));
        }

        Optional<User> optionalUser = userService.findByUsername(username);
        if (optionalUser.isEmpty() || optionalUser.get().getMfaSecret() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Bad Request",
                    "message", "MFA setup has not been initiated. Please call /api/auth/mfa/setup first."
            ));
        }

        User user = optionalUser.get();
        if (!totpService.verifyTotp(user.getMfaSecret(), request.getCode())) {
            publishSecurityEvent("MFA_ENABLE_FAILED", username, "FAILURE", "Invalid TOTP verification code");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Bad Request",
                    "message", "Invalid 6-digit MFA code. Please re-enter the code displayed in your authenticator app."
            ));
        }

        userService.enableMfa(username);
        return ResponseEntity.ok(Map.of(
                "status", HttpStatus.OK.value(),
                "message", "MFA enabled successfully. Your account is now protected with Two-Factor Authentication."
        ));
    }

    /**
     * Disables MFA on an account (requires valid 6-digit TOTP code).
     * POST /api/auth/mfa/disable
     */
    @PostMapping("/mfa/disable")
    public ResponseEntity<?> disableMfa(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody MfaVerifyRequest request) {

        String username = resolveAuthenticatedUsername(authHeader);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Authentication required"
            ));
        }

        Optional<User> optionalUser = userService.findByUsername(username);
        if (optionalUser.isEmpty() || !optionalUser.get().isMfaEnabled()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Bad Request",
                    "message", "MFA is not currently enabled on this account."
            ));
        }

        User user = optionalUser.get();
        if (!totpService.verifyTotp(user.getMfaSecret(), request.getCode())) {
            publishSecurityEvent("MFA_DISABLE_FAILED", username, "FAILURE", "Invalid TOTP code attempted during MFA disable");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Bad Request",
                    "message", "Invalid 6-digit MFA code."
            ));
        }

        userService.disableMfa(username);
        return ResponseEntity.ok(Map.of(
                "status", HttpStatus.OK.value(),
                "message", "MFA disabled successfully."
        ));
    }

    /**
     * Completes Step 2 of MFA authentication using the temporary token and 6-digit TOTP code.
     * POST /api/auth/mfa/verify-login
     */
    @PostMapping("/mfa/verify-login")
    public ResponseEntity<?> verifyMfaLogin(@Valid @RequestBody MfaLoginRequest request) {
        String tempToken = request.getTempToken();

        // 1. Verify that this is an RSA-signed temporary token
        if (!rsaJwtService.isMfaTempToken(tempToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Invalid or malformed temporary MFA token"
            ));
        }

        String username;
        try {
            username = rsaJwtService.extractUsername(tempToken);
            if (!rsaJwtService.isTokenValid(tempToken, username)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "status", HttpStatus.UNAUTHORIZED.value(),
                        "error", "Unauthorized",
                        "message", "Temporary MFA token has expired. Please sign in again."
                ));
            }
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Invalid temporary MFA token: " + ex.getMessage()
            ));
        }

        Optional<User> optionalUser = userService.findByUsername(username);
        if (optionalUser.isEmpty() || !optionalUser.get().isMfaEnabled()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Bad Request",
                    "message", "User not found or MFA is not enabled"
            ));
        }

        User user = optionalUser.get();

        // 2. Verify the 6-digit TOTP code against the user's secret
        if (!totpService.verifyTotp(user.getMfaSecret(), request.getCode())) {
            publishSecurityEvent("LOGIN_FAILED", username, "FAILURE", "Invalid 6-digit MFA code during Step 2 verification");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", "Unauthorized",
                    "message", "Invalid 6-digit MFA code"
            ));
        }

        // 3. Issue full RS256 Access Token and Refresh Token
        String role = rsaJwtService.extractRole(tempToken);
        if (role == null || role.isBlank()) {
            role = user.getRole();
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);

        String token = rsaJwtService.generateToken(claims, user.getUsername());

        String refreshTokenStr = null;
        if (refreshTokenService != null) {
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getUsername());
            refreshTokenStr = refreshToken.getToken();
        }

        publishSecurityEvent("LOGIN_SUCCESS", username, "SUCCESS", "User authenticated successfully via MFA");

        AuthResponse response = AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .role(role)
                .refreshToken(refreshTokenStr)
                .expiresInMs(1000 * 60 * 15) // 15 minutes
                .message("MFA authentication successful")
                .build();

        return ResponseEntity.ok(response);
    }

    private String resolveAuthenticatedUsername(String authHeader) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                String extracted = rsaJwtService.extractUsername(token);
                if (rsaJwtService.isTokenValid(token, extracted)) {
                    return extracted;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * List all current registered users.
     * GET /api/auth/users
     */
    @GetMapping("/users")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.findAll());
    }
}
