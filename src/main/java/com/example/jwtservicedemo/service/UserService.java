package com.example.jwtservicedemo.service;

import com.example.jwtservicedemo.audit.SecurityAuditEvent;
import com.example.jwtservicedemo.exception.AccountLockedException;
import com.example.jwtservicedemo.model.User;
import com.example.jwtservicedemo.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing User entities persisted in MySQL and serving as Spring Security's UserDetailsService.
 * Hashes passwords using BCrypt to protect against credential leaks and timing attacks.
 */
@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, User> inMemoryStore = new ConcurrentHashMap<>();

    @Autowired
    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, null);
    }

    public UserService(UserRepository userRepository) {
        this(userRepository, new BCryptPasswordEncoder(), null);
    }

    public UserService(PasswordEncoder passwordEncoder) {
        this(null, passwordEncoder, null);
    }

    public UserService() {
        this(null, new BCryptPasswordEncoder(), null);
    }

    private void publishSecurityEvent(String eventType, String username, String status, String details) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SecurityAuditEvent(this, eventType, username, status, details));
        }
    }

    @PostConstruct
    public void initDefaultUsers() {
        // Seed pre-existing admin user into database if not already present
        if (findByUsername("admin").isEmpty()) {
            register(User.builder()
                    .username("admin")
                    .password("admin")
                    .role("ADMIN")
                    .build());
        }
    }

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final long LOCK_TIME_DURATION_MILLIS = 15 * 60 * 1000L; // 15 minutes

    /**
     * Authenticates a user against credentials using BCrypt.
     * Enforces Account Lockout protection against distributed brute-force attacks:
     * - Locks account for 15 minutes after 5 consecutive bad passwords.
     * - Automatically unlocks after 15 minutes.
     * - Resets failed attempts counter on successful login.
     * - Returns empty without leaking user existence for unknown users.
     */
    public Optional<User> authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            publishSecurityEvent("LOGIN_FAILED", username != null ? username : "anonymous", "FAILURE", "Missing credentials");
            return Optional.empty();
        }

        Optional<User> optionalUser = findByUsername(username);
        if (optionalUser.isEmpty()) {
            publishSecurityEvent("LOGIN_FAILED", username, "FAILURE", "User not found");
            return Optional.empty();
        }

        User user = optionalUser.get();

        // Check if account is currently locked
        if (user.getLockTime() != null) {
            long lockTimeMillis = user.getLockTime().toEpochMilli();
            long nowMillis = System.currentTimeMillis();

            if (nowMillis < lockTimeMillis + LOCK_TIME_DURATION_MILLIS) {
                long remainingMillis = (lockTimeMillis + LOCK_TIME_DURATION_MILLIS) - nowMillis;
                long remainingMinutes = Math.max(1, (remainingMillis + 59999) / 60000);
                publishSecurityEvent("ACCOUNT_LOCKED", username, "ALERT",
                        "Attempted authentication on locked account. " + remainingMinutes + " minute(s) remaining.");
                throw new AccountLockedException(
                        "Account '" + username + "' is temporarily locked due to " + MAX_FAILED_ATTEMPTS
                                + " consecutive failed login attempts. Try again in " + remainingMinutes + " minute(s).",
                        remainingMinutes
                );
            } else {
                // Lock duration elapsed -> Auto-unlock
                user.setLockTime(null);
                user.setFailedAttempts(0);
                saveUser(user);
            }
        }

        // Validate password
        if (passwordEncoder.matches(rawPassword, user.getPassword())) {
            // Reset failed attempts if any existed
            if (user.getFailedAttempts() > 0) {
                user.setFailedAttempts(0);
                saveUser(user);
            }
            publishSecurityEvent("LOGIN_SUCCESS", username, "SUCCESS", "User authenticated successfully");
            return Optional.of(user);
        } else {
            // Increment failed attempts
            int newAttempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(newAttempts);

            if (newAttempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockTime(Instant.now());
                saveUser(user);
                publishSecurityEvent("ACCOUNT_LOCKED", username, "ALERT",
                        "Account locked for 15 minutes due to " + MAX_FAILED_ATTEMPTS + " consecutive failed login attempts");
                throw new AccountLockedException(
                        "Account '" + username + "' has been locked for 15 minutes due to " + MAX_FAILED_ATTEMPTS
                                + " consecutive failed login attempts.",
                        15
                );
            } else {
                saveUser(user);
                publishSecurityEvent("LOGIN_FAILED", username, "FAILURE",
                        "Invalid credentials. Attempt " + newAttempts + " of " + MAX_FAILED_ATTEMPTS);
            }
            return Optional.empty();
        }
    }

    /**
     * Registers a new user with BCrypt hashed password in MySQL database.
     */
    public User register(User user) {
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("USER");
        }
        String rawPassword = user.getPassword();
        if (rawPassword != null && !rawPassword.startsWith("$2a$") && !rawPassword.startsWith("$2b$")) {
            user.setPassword(passwordEncoder.encode(rawPassword));
        }
        return saveUser(user);
    }

    /**
     * Saves user to database or in-memory fallback.
     */
    public User saveUser(User user) {
        if (userRepository != null) {
            return userRepository.save(user);
        }
        inMemoryStore.put(user.getUsername(), user);
        return user;
    }

    /**
     * Unlocks an account and resets failed attempt counters.
     */
    public void unlockAccount(String username) {
        findByUsername(username).ifPresent(user -> {
            user.setLockTime(null);
            user.setFailedAttempts(0);
            saveUser(user);
        });
    }

    /**
     * Explicitly locks an account.
     */
    public void lockAccount(String username) {
        findByUsername(username).ifPresent(user -> {
            user.setLockTime(Instant.now());
            user.setFailedAttempts(MAX_FAILED_ATTEMPTS);
            saveUser(user);
        });
    }

    /**
     * Finds a user by username.
     */
    public Optional<User> findByUsername(String username) {
        if (userRepository != null) {
            return userRepository.findByUsername(username);
        }
        return Optional.ofNullable(inMemoryStore.get(username));
    }

    /**
     * Returns all registered users from database.
     */
    public List<User> findAll() {
        if (userRepository != null) {
            return userRepository.findAll();
        }
        return new ArrayList<>(inMemoryStore.values());
    }

    /**
     * Updates an existing user's role (Admin operation).
     */
    public User updateRole(String username, String newRole) {
        User user = findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String sanitizedRole = newRole.trim().toUpperCase();
        if (sanitizedRole.startsWith("ROLE_")) {
            sanitizedRole = sanitizedRole.substring(5);
        }
        user.setRole(sanitizedRole);
        if (userRepository != null) {
            return userRepository.save(user);
        }
        inMemoryStore.put(user.getUsername(), user);
        return user;
    }

    /**
     * Verifies if a user's actual database role can assume the requested role under the hierarchy:
     * ADMIN -> MANAGER -> USER.
     */
    public boolean canAssumeRole(String actualUserRole, String requestedRole) {
        if (actualUserRole == null || requestedRole == null) {
            return false;
        }
        String actual = actualUserRole.trim().toUpperCase().replace("ROLE_", "");
        String requested = requestedRole.trim().toUpperCase().replace("ROLE_", "");

        if (actual.equals(requested)) {
            return true;
        }
        if ("ADMIN".equals(actual)) {
            return "MANAGER".equals(requested) || "USER".equals(requested);
        }
        if ("MANAGER".equals(actual)) {
            return "USER".equals(requested);
        }
        return false;
    }

    /**
     * Spring Security UserDetailsService implementation.
     * Loads User by username and maps roles to GrantedAuthorities.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        String role = user.getRole();
        if (role == null || role.isBlank()) {
            role = "USER";
        }
        if (!role.toUpperCase().startsWith("ROLE_")) {
            role = "ROLE_" + role.toUpperCase();
        }

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );
    }

    /**
     * Stores an unconfirmed MFA Base32 secret for a user.
     */
    public void setMfaSecret(String username, String secret) {
        findByUsername(username).ifPresent(user -> {
            user.setMfaSecret(secret);
            saveUser(user);
        });
    }

    /**
     * Activates MFA for a user after verifying their initial TOTP code.
     */
    public void enableMfa(String username) {
        findByUsername(username).ifPresent(user -> {
            user.setMfaEnabled(true);
            saveUser(user);
            publishSecurityEvent("MFA_ENABLED", username, "SUCCESS", "Multi-Factor Authentication enabled");
        });
    }

    /**
     * Deactivates MFA for a user.
     */
    public void disableMfa(String username) {
        findByUsername(username).ifPresent(user -> {
            user.setMfaEnabled(false);
            user.setMfaSecret(null);
            saveUser(user);
            publishSecurityEvent("MFA_DISABLED", username, "SUCCESS", "Multi-Factor Authentication disabled");
        });
    }
}
