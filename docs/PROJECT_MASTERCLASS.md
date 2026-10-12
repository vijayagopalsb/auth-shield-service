# 🎓 Enterprise Identity & Security Masterclass Guide
### Complete Reference Architecture, Cryptographic Mechanics, Attack Mitigations & Interview Playbook

> **Project:** `auth-shield-service` (AuthShield)  
> **Tech Stack:** Java 21, Spring Boot 3.x / 4.x, Spring Security 6, JJWT 0.12.x, Bucket4j, MySQL 8.4, Docker  
> **Test Suite:** 82 Automated Integration Tests (100% Pass Rate)  
> **Target Audience:** Junior to Principal Software Engineers, Security Architects, and Technical Interview Candidates

---

## 📑 Table of Contents
1. [Executive Summary & Architectural Philosophy](#1-executive-summary--architectural-philosophy)
2. [The 30,000-Foot Architecture & Request Journey](#2-the-30000-foot-architecture--request-journey)
3. [The 10 Production Security Pillars](#3-the-10-production-security-pillars)
   - [Pillar 1: Asymmetric Cryptography (RS256 vs. HS256)](#pillar-1-asymmetric-cryptography-rs256-vs-hs256)
   - [Pillar 2: RFC 7517 JWKS Public Key Endpoint](#pillar-2-rfc-7517-jwks-public-key-endpoint)
   - [Pillar 3: Hierarchical Role-Based Access Control (RBAC)](#pillar-3-hierarchical-role-based-access-control-rbac)
   - [Pillar 4: Bucket4j Token Bucket Rate Limiting](#pillar-4-bucket4j-token-bucket-rate-limiting)
   - [Pillar 5: Refresh Token Rotation (RTR) & Replay Theft Detection](#pillar-5-refresh-token-rotation-rtr--replay-theft-detection)
   - [Pillar 6: Stateless Access Token Blacklisting on Logout](#pillar-6-stateless-access-token-blacklisting-on-logout)
   - [Pillar 7: Account Lockout & Brute-Force Guard](#pillar-7-account-lockout--brute-force-guard)
   - [Pillar 8: OWASP Web Hardening & ASVS Password Security](#pillar-8-owasp-web-hardening--asvs-password-security)
   - [Pillar 9: SIEM Security Audit Event Publishing (SOC 2 / ISO 27001)](#pillar-9-siem-security-audit-event-publishing-soc-2--iso-27001)
   - [Pillar 10: Multi-Factor Authentication (MFA / TOTP RFC 6238)](#pillar-10-multi-factor-authentication-mfa--totp-rfc-6238)
4. [Complete Codebase Map: All 33 Files Explained](#4-complete-codebase-map-all-33-files-explained)
5. [The 5 Critical Real-World Request Lifecycles](#5-the-5-critical-real-world-request-lifecycles)
6. [Senior Interview Playbook: 10 Tough Questions & Model Answers](#6-senior-interview-playbook-10-tough-questions--model-answers)
7. [Production Operations & Docker Reference](#7-production-operations--docker-reference)

---

## 1. Executive Summary & Architectural Philosophy

### The Evolution: From Tutorial to Enterprise Identity Provider
Most developers begin their authentication journey with a tutorial:
- A single symmetric secret string (`mySecretKey1234567890`) stored in `application.properties`.
- Hardcoded in-memory user lists.
- No refresh tokens or static database refresh tokens that never expire.
- No rate limiting, leaving the service open to brute-force attacks and denial-of-service.
- No way to revoke a token when a user clicks "Logout".

**`auth-shield-service`** transforms this concept into a **hardened Identity & Access Management (IAM) Microservice** modeled after industry standards like Auth0, Keycloak, and Okta. It is decoupled, cryptographically sound, and resilient against modern attack vectors.

### Core Architectural Decisions
1. **Decoupled Verification:** Other microservices verify user identity purely through mathematical signatures (RSA-2048 Public Key) without calling this database.
2. **Defense in Depth:** Attacks are filtered in layers: network bursts are stopped by Bucket4j, credential guessing is stopped by Account Lockout, token theft is stopped by RTR, and session hijacking is neutralized by MFA.
3. **Zero-Trust Auditability:** Every authentication action, failure, and privilege change produces an immutable event stream for compliance.

---

## 2. The 30,000-Foot Architecture & Request Journey

When an HTTP request reaches `auth-shield-service`, it navigates **4 distinct defensive perimeters** before reaching business logic:

```
                          ┌────────────────────────────────────────────────────────┐
                          │                   Incoming HTTP Request                │
                          └────────────────────────────────────────────────────────┘
                                                      │
══════════════════════════════════════════════════════╪══════════════════════════════════════════════════════
  PERIMETER 1: SURGE & BOT SHIELD                     │
  Bucket4j Token Bucket                               ▼
                                         ┌────────────────────────┐
                                         │   RateLimitingFilter   │─── Limit Exceeded ──► [HTTP 429 Too Many Requests]
                                         └────────────────────────┘
                                                      │ Allowed
══════════════════════════════════════════════════════╪══════════════════════════════════════════════════════
  PERIMETER 2: CRYPTOGRAPHIC AUTHENTICATION           │
  RS256 Asymmetric RSA Signature                      ▼
                                         ┌────────────────────────┐
                                         │ JwtAuthenticationFilter│─── Blacklisted / MFA Temp ──► [Filter Pass / Anonymous]
                                         └────────────────────────┘
                                                      │ Valid
                                                      ▼
                                          Populates SecurityContext
                                          (Username + GrantedAuthority)
══════════════════════════════════════════════════════╪══════════════════════════════════════════════════════
  PERIMETER 3: ACCESS CONTROL & HIERARCHY             │
  ADMIN > MANAGER > USER                              ▼
                                         ┌────────────────────────┐
                                         │   SecurityFilterChain  │─── Insufficient Role ──► [HTTP 403 Forbidden]
                                         │     + @PreAuthorize    │─── Unauthenticated   ──► [HTTP 401 Unauthorized]
                                         └────────────────────────┘
                                                      │ Authorized
══════════════════════════════════════════════════════╪══════════════════════════════════════════════════════
  PERIMETER 4: BUSINESS LOGIC & AUDIT                 │
  Controllers, Services & Storage                     ▼
                                         ┌────────────────────────┐
                                         │      Controllers       │
                                         └────────────────────────┘
                                            │                  │
                            ┌───────────────┘                  └───────────────┐
                            ▼                                                  ▼
                 ┌─────────────────────┐                            ┌─────────────────────┐
                 │    Core Services    │                            │    Security Audit   │
                 │ (User, RTR, TOTP)   │                            │ (Event Publisher)   │
                 └─────────────────────┘                            └─────────────────────┘
                            │                                                  │
                            ▼                                                  ▼
                 ┌─────────────────────┐                            ┌─────────────────────┐
                 │ MySQL: `users`,     │                            │ MySQL: `security_   │
                 │ `refresh_tokens`    │                            │         audit_logs` │
                 └─────────────────────┘                            └─────────────────────┘
```

---

## 3. The 10 Production Security Pillars

---

### Pillar 1: Asymmetric Cryptography (RS256 vs. HS256)

#### The Problem with Symmetric Signing (HS256)
In `HS256`, the exact same secret key is used to **both create and verify** tokens:
```
Auth Service (Signs with SECRET)  ───►  Payment Service (Verifies with SECRET)
```
If an attacker breaches the Payment Service, they steal `SECRET` and can now **forge valid tokens for the entire enterprise**, claiming to be an `ADMIN` or the CEO.

#### The Solution: Asymmetric RSA-2048 (RS256)
`auth-shield-service` splits cryptography into two mathematically linked keys:
1. **Private Key (2048-bit RSA, `private_key.pem`):** Kept strictly inside `auth-shield-service`. Used exclusively to sign tokens.
2. **Public Key (2048-bit RSA, `public_key.pem`):** Distributed to downstream microservices and frontend clients. Can only *verify* signatures; it cannot forge them.

```
       ┌────────────────────────┐
       │   auth-shield-service     │
       │ (Holds PRIVATE Key)    │
       └────────────────────────┘
                   │
                   │ Signs Token (RS256)
                   ▼
       [eyJhbGciOiJSUzI1NiIs...]
                   │
         ┌─────────┴─────────┐
         ▼                   ▼
┌──────────────────┐┌──────────────────┐
│ Payment Service  ││  Order Service   │
│ (Holds PUBLIC Key││(Holds PUBLIC Key │
│  Only Verifies)  ││  Only Verifies)  │
└──────────────────┘└──────────────────┘
```

**Implementation:** [`RsaJwtService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/RsaJwtService.java) loads the keys using `PKCS8EncodedKeySpec` (private) and `X509EncodedKeySpec` (public).

---

### Pillar 2: RFC 7517 JWKS Public Key Endpoint

#### What is JWKS?
Rather than emailing public key certificates to other teams, standard microservices expose their public keys via **JSON Web Key Set (RFC 7517)**:
`GET /.well-known/jwks.json`

#### Example JSON Response from `auth-shield-service`:
```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "kid": "jwt-service-rsa-key-1",
      "alg": "RS256",
      "n": "uH7F2K... (Base64URL Modulus)",
      "e": "AQAB"
    }
  ]
}
```
Downstream microservices (Spring Cloud Gateway, Node.js, Python FastAPI) fetch this endpoint once at startup and cache the public key. When verifying tokens, they check the token's header `kid` (Key ID), look up the matching key from their cache, and verify the signature locally with zero database overhead.

**Implementation:** [`JwksController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/JwksController.java).

---

### Pillar 3: Hierarchical Role-Based Access Control (RBAC)

#### The Problem: Flat Role Checking
In standard Spring Security, if an endpoint is protected by `@PreAuthorize("hasRole('USER')")`, an administrator with `ROLE_ADMIN` cannot access it unless the database explicitly gives them `ROLE_ADMIN, ROLE_USER`. This leads to role pollution.

#### The Solution: Role Hierarchy
We define an explicit hierarchy:
$$\text{ROLE\_ADMIN} \implies \text{ROLE\_MANAGER} \implies \text{ROLE\_USER}$$

```java
@Bean
public RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN").implies("MANAGER")
            .role("MANAGER").implies("USER")
            .build();
}
```
- An `ADMIN` automatically has access to `ADMIN`, `MANAGER`, and `USER` endpoints.
- A `MANAGER` automatically has access to `MANAGER` and `USER` endpoints.
- A `USER` only has access to `USER` endpoints.

**Implementation:** [`SecurityConfig.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/config/SecurityConfig.java) and [`HierarchicalAccessController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/HierarchicalAccessController.java).

---

### Pillar 4: Bucket4j Token Bucket Rate Limiting

#### How the Token Bucket Works
Imagine each client IP has a physical bucket:
- **Bucket Capacity:** Maximum number of tokens the bucket can hold.
- **Refill Rate:** How fast new tokens drip into the bucket over time.
- **Consumption:** Every incoming HTTP request attempts to consume 1 token.
  - If tokens $> 0$: Token is consumed, request proceeds.
  - If tokens $= 0$: Request is rejected with **HTTP 429 Too Many Requests**.

```
                Incoming HTTP Request
                          │
                          ▼
              ┌───────────────────────┐
              │ Bucket for Client IP  │
              │  (Capacity: 5 tokens) │
              │  [ 🪙 🪙 🪙 ]         │
              └───────────────────────┘
                          │
               ┌──────────┴──────────┐
               ▼                     ▼
          Tokens > 0            Tokens = 0
       Consume 1 Token        Reject Request
       Pass to Next Filter   [HTTP 429 Too Many Requests]
```

#### Why Place Rate Limiting Before Spring Security?
Spring Security performs BCrypt password hashing, database queries, and cryptographic signature verifications. These are **CPU-intensive**. If rate limiting sat *after* Spring Security, a bot could flood the server with invalid logins, maxing out CPU cores at 100%. By placing `RateLimitingFilter` at the very front of the servlet chain, unauthenticated flood traffic is dropped in sub-milliseconds without touching CPU or database resources.

**Implementation:** [`RateLimitingFilter.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/ratelimit/RateLimitingFilter.java) and [`RateLimitingService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/ratelimit/RateLimitingService.java).

---

### Pillar 5: Refresh Token Rotation (RTR) & Replay Theft Detection

#### The Problem: Refresh Token Theft
Access tokens expire quickly (15 minutes), but refresh tokens live for days. If an attacker intercepts a user's refresh token from network traffic or browser storage, the attacker could theoretically generate fresh access tokens indefinitely.

#### The Solution: Automatic Rotation & Lineage Tracking
Every time a refresh token is used to generate a new access token:
1. The submitted token is immediately marked `revoked = true`.
2. A brand-new refresh token is issued.
3. The old token records the new token in `replacedByToken`.

#### Automatic Replay Breach Detection:
If an attacker attempts to use an **already revoked token** (a replay attack):
1. The service detects that `token.isRevoked() == true`.
2. This proves that a token was used twice—indicating theft!
3. The service triggers `revokeAllByUser(user)`, **killing every active session for that user across all devices**.
4. Both the attacker and the legitimate user are locked out, requiring full re-authentication and neutralizing the theft.

**Implementation:** [`RefreshTokenService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/service/RefreshTokenService.java).

---

### Pillar 6: Stateless Access Token Blacklisting on Logout

#### The Classic JWT Revocation Problem
JWTs are stateless; once signed, they are self-contained. When a user clicks "Logout", discarding the token in the browser does not stop an attacker who already copied the token header from continuing to call the API until the token expires.

#### The Solution: JTI In-Memory Blacklisting
1. Every JWT generated by `RsaJwtService` includes a unique UUID in its `jti` (JWT ID) claim:
   ```json
   { "jti": "8f3a1c2d-9b4e-...", "sub": "alice", "exp": 1712345678 }
   ```
2. On `POST /api/auth/logout`, `TokenBlacklistService` stores the token's `jti` in a high-performance in-memory cache until its expiration timestamp.
3. On every incoming request, `JwtAuthenticationFilter` checks:
   ```java
   if (tokenBlacklistService.isBlacklisted(jwt)) {
       // Reject immediately!
   }
   ```
4. Once the token's expiration timestamp passes naturally, it is evicted from memory. Memory consumption remains bounded and small.

**Implementation:** [`TokenBlacklistService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/TokenBlacklistService.java) and [`JwtAuthenticationFilter.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/JwtAuthenticationFilter.java).

---

### Pillar 7: Account Lockout & Brute-Force Guard

#### Why Rate Limiting Alone Is Not Enough
An attacker using a botnet of 10,000 compromised residential IP addresses can send only 1 request per IP per minute. They bypass IP rate limiting entirely while still firing 10,000 attempts per minute against a targeted account (e.g. `admin`).

#### The Solution: Account-Level Failure Tracking
`UserService` tracks consecutive password failures directly on the user record in MySQL:
1. Failures 1 through 4: Increment `failedAttempts` counter. Return HTTP 401 Unauthorized.
2. Failure 5: Set `lockTime = Instant.now()`. Return **HTTP 423 Locked** with message:
   `"Account is temporarily locked due to 5 consecutive failed login attempts. Try again in 15 minute(s)."`
3. For the next 15 minutes, authentication attempts are blocked immediately without evaluating passwords.
4. Auto-Unlock: Once 15 minutes have passed, the user record automatically resets on the next attempt.

**Implementation:** [`UserService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/service/UserService.java#L70-L135) and [`AccountLockedException.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/exception/AccountLockedException.java).

---

### Pillar 8: OWASP Web Hardening & ASVS Password Security

#### OWASP ASVS Password Policy
To comply with OWASP Application Security Verification Standard (ASVS 4.0), [`RegisterRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/RegisterRequest.java) enforces:
- Minimum 8 characters.
- At least 1 uppercase letter (`A-Z`).
- At least 1 lowercase letter (`a-z`).
- At least 1 numeric digit (`0-9`).
- At least 1 special symbol (`@$!%*?&#^`).

#### OWASP Security Headers (Configured in `SecurityConfig`)
- `Strict-Transport-Security (HSTS):` Enforces HTTPS with preload.
- `Content-Security-Policy (CSP):` Restricts executable scripts (`default-src 'self'`).
- `X-Frame-Options: DENY:` Eliminates Clickjacking attacks.
- `X-Content-Type-Options: nosniff:` Prevents MIME-type sniffing exploits.
- `Referrer-Policy: strict-origin-when-cross-origin:` Shields authorization credentials from leaking in referer headers.

**Implementation:** [`SecurityConfig.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/config/SecurityConfig.java#L140-L155).

---

### Pillar 9: SIEM Security Audit Event Publishing (SOC 2 / ISO 27001)

#### The Architecture: Decoupled Domain Events
Auditing is completely decoupled from business operations using Spring's `ApplicationEventPublisher`:
```
   UserService / RefreshTokenService / AuthController
                          │
                          │ publishEvent(SecurityAuditEvent)
                          ▼
            ApplicationEventPublisher (Spring)
                          │
                          ▼
             SecurityAuditEventListener
               │                       │
               ▼                       ▼
      Structured Logs        MySQL Table
   (ELK, Splunk, Datadog)  (`security_audit_logs`)
```

#### The 6 Audited Security Events:
1. `LOGIN_SUCCESS`: Records user login, timestamp, and metadata.
2. `LOGIN_FAILED`: Records invalid passwords or non-existent usernames.
3. `ACCOUNT_LOCKED`: Alerts on brute-force threshold breaches.
4. `TOKEN_REUSE_BREACH`: High-severity security alarm on refresh token replay attacks.
5. `ROLE_UPDATED`: Tracks privilege escalations (Admin operations).
6. `LOGOUT`: Records session termination.

**Implementation:** [`SecurityAuditEvent.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/audit/SecurityAuditEvent.java) and [`SecurityAuditEventListener.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/audit/SecurityAuditEventListener.java).

---

### Pillar 10: Multi-Factor Authentication (MFA / TOTP RFC 6238)

#### The Mechanics: Pure Math, $0 Cost
TOTP (RFC 6238) requires **zero SMS gateways** and **zero internet access**. Both the server and Google Authenticator generate the exact same 6-digit number independently:
$$T = \lfloor \frac{\text{Current Epoch Time in Seconds}}{30} \rfloor$$
$$\text{Code} = \text{HMAC-SHA1}(\text{Base32 Secret}, T) \pmod{10^6}$$

#### The Two-Step Login Lifecycle:
1. **Step 1 (Username + Password):**
   - User submits credentials to `POST /api/auth/login`.
   - If `user.isMfaEnabled() == true`, full access tokens are **not** returned.
   - The server issues a short-lived, restricted **`MFA_TEMP` token** (valid for 5 minutes).
2. **Step 2 (TOTP Verification):**
   - User submits `tempToken` and the 6-digit code to `POST /api/auth/mfa/verify-login`.
   - The server verifies the HMAC-SHA1 code (allowing $\pm 1$ time window for clock drift).
   - If valid, the server issues the full RS256 Access Token and Refresh Token!

**Implementation:** [`TotpService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/mfa/TotpService.java) and [`AuthController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/AuthController.java#L460-L530).

---

## 4. Complete Codebase Map: All 33 Files Explained

Here is the complete roster of source files in `src/main/java`:

| # | Package | File | Responsibility |
| :---: | :--- | :--- | :--- |
| **1** | `com.example.jwtservicedemo` | [`JwtServiceDemoApplication.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/JwtServiceDemoApplication.java) | Main entry point bootstrapping Spring Boot, JPA, and Security. |
| **2** | `audit` | [`SecurityAuditEvent.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/audit/SecurityAuditEvent.java) | Domain event extending `ApplicationEvent` carrying event type, username, status, and details. |
| **3** | `audit` | [`SecurityAuditEventListener.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/audit/SecurityAuditEventListener.java) | `@EventListener` component emitting SIEM logs and persisting to MySQL. |
| **4** | `config` | [`ApplicationConfig.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/config/ApplicationConfig.java) | Provides infrastructure beans like `BCryptPasswordEncoder`. |
| **5** | `config` | [`SecurityConfig.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/config/SecurityConfig.java) | Master SecurityFilterChain: CORS, CSRF disable, RoleHierarchy, security headers, and endpoint permissions. |
| **6** | `controller` | [`AuthController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/AuthController.java) | REST endpoints for register, login, refresh, logout, MFA setup/enable/verify, and audit querying. |
| **7** | `controller` | [`HierarchicalAccessController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/HierarchicalAccessController.java) | Demo endpoints testing hierarchical RBAC (`/admin-only`, `/manager-area`, `/user-area`). |
| **8** | `controller` | [`JwksController.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/controller/JwksController.java) | RFC 7517 endpoint (`/.well-known/jwks.json`) publishing the RSA Public Key. |
| **9** | `dto` | [`AuthRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/AuthRequest.java) | Login request payload (`username`, `password`, optional `role`). |
| **10**| `dto` | [`AuthResponse.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/AuthResponse.java) | Login response payload (`token`, `refreshToken`, `mfaRequired`, `tempToken`). |
| **11**| `dto` | [`MfaLoginRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/MfaLoginRequest.java) | Step 2 payload (`tempToken`, `code`). |
| **12**| `dto` | [`MfaSetupResponse.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/MfaSetupResponse.java) | Enrollment payload (`secret`, `otpAuthUri`, `qrCodeDataUrl`). |
| **13**| `dto` | [`MfaVerifyRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/MfaVerifyRequest.java) | Enable/disable payload with 6-digit code. |
| **14**| `dto` | [`RefreshTokenRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/RefreshTokenRequest.java) | Token refresh and logout payload (`refreshToken`). |
| **15**| `dto` | [`RegisterRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/RegisterRequest.java) | User registration with OWASP ASVS regex password validation. |
| **16**| `dto` | [`RoleUpdateRequest.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/dto/RoleUpdateRequest.java) | Admin role promotion payload (`role`). |
| **17**| `exception` | [`AccountLockedException.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/exception/AccountLockedException.java) | Custom exception thrown when a user exceeds 5 failed login attempts. |
| **18**| `exception` | [`GlobalExceptionHandler.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/exception/GlobalExceptionHandler.java) | `@RestControllerAdvice` mapping exceptions to HTTP status codes (e.g. 423 Locked). |
| **19**| `jwt` | [`JwtAccessDeniedHandler.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/JwtAccessDeniedHandler.java) | Emits clean JSON responses for HTTP 403 Forbidden. |
| **20**| `jwt` | [`JwtAuthenticationEntryPoint.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/JwtAuthenticationEntryPoint.java) | Emits clean JSON responses for HTTP 401 Unauthorized. |
| **21**| `jwt` | [`JwtAuthenticationFilter.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/JwtAuthenticationFilter.java) | Intercepts HTTP requests, validates RSA token, checks blacklist, populates SecurityContext. |
| **22**| `jwt` | [`JwtService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/JwtService.java) | Early symmetric HS256 service preserved for baseline reference. |
| **23**| `jwt` | [`RsaJwtService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/RsaJwtService.java) | Asymmetric RS256 token engine (signing with Private Key, verifying with Public Key, temp tokens). |
| **24**| `jwt` | [`TokenBlacklistService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/jwt/TokenBlacklistService.java) | In-memory token revocation cache for immediate logout invalidation. |
| **25**| `mfa` | [`TotpService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/mfa/TotpService.java) | RFC 6238 TOTP computation (HMAC-SHA1, Base32 codec, ZXing QR generation). |
| **26**| `model` | [`RefreshToken.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/model/RefreshToken.java) | JPA entity for refresh tokens (`token`, `expiryDate`, `revoked`, `replacedByToken`). |
| **27**| `model` | [`SecurityAuditLog.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/model/SecurityAuditLog.java) | JPA entity for immutable audit logs (`eventType`, `username`, `status`, `details`, `timestamp`). |
| **28**| `model` | [`User.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/model/User.java) | JPA entity for users (`username`, `password`, `role`, `failedAttempts`, `lockTime`, `mfaEnabled`, `mfaSecret`). |
| **29**| `ratelimit` | [`RateLimitingFilter.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/ratelimit/RateLimitingFilter.java) | Servlet filter evaluating client IP token buckets, returning HTTP 429 on exhaustion. |
| **30**| `ratelimit` | [`RateLimitingService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/ratelimit/RateLimitingService.java) | Caffeine-backed in-memory Bucket4j token bucket provider. |
| **31**| `repository`| [`RefreshTokenRepository.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/repository/RefreshTokenRepository.java) | Spring Data JPA repository for refresh token persistence. |
| **32**| `repository`| [`SecurityAuditLogRepository.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/repository/SecurityAuditLogRepository.java) | Spring Data JPA repository for audit history querying. |
| **33**| `repository`| [`UserRepository.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/repository/UserRepository.java) | Spring Data JPA repository for user credentials and lockout state. |
| **34**| `service` | [`RefreshTokenService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/service/RefreshTokenService.java) | Implements Refresh Token Rotation and Automatic Replay Breach Detection. |
| **35**| `service` | [`UserService.java`](file:///D:/MicroServices_Study/auth-shield-service/src/main/java/com/example/jwtservicedemo/service/UserService.java) | Manages BCrypt validation, account lockout rules, user registration, and MFA state. |

---

## 5. The 5 Critical Real-World Request Lifecycles

### Lifecycle 1: Normal Login (No MFA)
1. Client sends `POST /api/auth/login` with `{"username": "alice", "password": "Password#123"}`.
2. `RateLimitingFilter` checks client IP bucket $\rightarrow$ Allowed.
3. `SecurityConfig` matches `/api/auth/login` under `.permitAll()` $\rightarrow$ Request reaches controller.
4. `UserService.authenticate()` verifies BCrypt password against `users` table $\rightarrow$ Matches!
5. Failed attempts reset to 0; `LOGIN_SUCCESS` event is published.
6. `SecurityAuditEventListener` logs SIEM line and saves to `security_audit_logs`.
7. `RsaJwtService` signs access token with Private Key (`exp`: 15 min).
8. `RefreshTokenService` creates UUID refresh token (`exp`: 7 days) and saves to MySQL.
9. Controller returns HTTP 200 with tokens.

---

### Lifecycle 2: Login with MFA Enabled
1. Client sends `POST /api/auth/login` with username and password.
2. `UserService.authenticate()` confirms password.
3. `AuthController` inspects `user.isMfaEnabled()` $\rightarrow$ It is `true`!
4. The controller **does not** issue access or refresh tokens. Instead, it issues an `MFA_TEMP` token (valid 5 minutes):
   ```json
   { "mfaRequired": true, "tempToken": "eyJhbGciOiJSUzI1NiIs..." }
   ```
5. If the client tries to use `tempToken` to access `/api/demo/user-area`, `JwtAuthenticationFilter` inspects `rsaJwtService.isMfaTempToken()` and rejects it (HTTP 401).
6. User opens Google Authenticator, reads 6-digit code `482910`, and calls `POST /api/auth/mfa/verify-login`.
7. `TotpService.verifyTotp()` computes HMAC-SHA1 $\rightarrow$ Valid!
8. Full RS256 Access Token and Refresh Token are issued.

---

### Lifecycle 3: Brute-Force Password Guessing
1. Attacker fires 5 incorrect passwords to `POST /api/auth/login`.
2. Attempts 1 to 4: `UserService` increments `failedAttempts` $\rightarrow$ Returns HTTP 401.
3. Attempt 5: `UserService` sets `lockTime = Instant.now()` and throws `AccountLockedException`.
4. `GlobalExceptionHandler` catches the exception $\rightarrow$ Returns **HTTP 423 Locked** with exact remaining minutes.
5. `SecurityAuditEvent` (`ACCOUNT_LOCKED`) is published with status `ALERT`.
6. For the next 15 minutes, even if the attacker submits the *correct* password, login is refused immediately.

---

### Lifecycle 4: Stolen Refresh Token Replay (RTR Breach)
1. Legitimate user rotates refresh token: submits Token A, receives Token B.
2. Token A is marked `revoked = true` with `replacedByToken = "B"`.
3. An attacker stole Token A and submits Token A to `POST /api/auth/refresh`.
4. `RefreshTokenService.rotateRefreshToken("A")` detects `token.isRevoked() == true`!
5. Service triggers `revokeAllByUser(user)` $\rightarrow$ Revokes Token B and all other active sessions for that user.
6. Service fires `TOKEN_REUSE_BREACH` alert event and throws `SecurityException` $\rightarrow$ Returns HTTP 403 Forbidden.
7. Both the attacker and the legitimate user are forced to re-authenticate from scratch.

---

### Lifecycle 5: User Logout
1. User calls `POST /api/auth/logout` with `Authorization: Bearer <jwt>` and `{"refreshToken": "..."}`.
2. `TokenBlacklistService` extracts `jti` from JWT and stores it in the in-memory blacklist.
3. `RefreshTokenService` marks the refresh token as revoked.
4. `LOGOUT` audit event is published.
5. If anyone submits that same JWT 1 second later, `JwtAuthenticationFilter` rejects it immediately.

---

## 6. Senior Interview Playbook: 10 Tough Questions & Model Answers

### Q1: "Why did you use RS256 instead of HS256 for JWT signing?"
> *"In a microservices architecture, HS256 is a major security risk because it is symmetric. Every downstream service (Payment, Order, Shipping) would need the secret key to verify tokens. If any single microservice is compromised, the attacker steals the key and can forge tokens for the entire company.*  
> *With RS256, my auth service keeps the 2048-bit RSA Private Key strictly secure, and publishes the Public Key via an RFC 7517 JWKS endpoint. Downstream services verify tokens cryptographically without ever being able to forge them or needing database access."*

---

### Q2: "JWT is stateless. How does your service handle logout revocation?"
> *"I implemented a dual revocation strategy:  
> 1. Long-term state (Refresh Tokens) is revoked immediately in MySQL.  
> 2. For short-lived access tokens (15 minutes), I implemented an in-memory TTL blacklist using unique JWT IDs (`jti`). When a user logs out, the `jti` is added to the blacklist cache with a TTL equal to the token's remaining lifetime. The `JwtAuthenticationFilter` checks this cache on every request. Once the token expires naturally, it is automatically evicted, keeping memory usage minimal."*

---

### Q3: "What happens if a hacker intercepts and uses a refresh token?"
> *"I implemented Refresh Token Rotation (RTR) with Automatic Replay Detection. Every time a refresh token is used, it is revoked and replaced. If an attacker replays an old token, my service detects that an already-revoked token was presented. This indicates a token theft event. In response, the service immediately invalidates ALL active refresh tokens for that user, killing every active session and firing a `TOKEN_REUSE_BREACH` security alarm."*

---

### Q4: "How does your service defend against distributed brute-force attacks from 10,000 rotating IPs?"
> *"IP-based rate limiting alone fails against distributed botnets. Therefore, I built a two-tiered defense:  
> 1. Perimeter defense: Bucket4j token bucket rate limiting at the servlet filter level to block single-IP surges before touching the database.  
> 2. Account-level defense: My `UserService` tracks consecutive failures per account. On the 5th consecutive failure across any combination of IPs, the account is locked for 15 minutes, returning HTTP 423 Locked. This halts distributed brute-force attacks regardless of IP spoofing."*

---

### Q5: "How does TOTP Multi-Factor Authentication work without sending an SMS?"
> *"It uses the RFC 6238 standard, which is pure mathematics with zero telephony costs. The server and the user's authenticator app share a 160-bit Base32 secret during setup. Every 30 seconds, both sides divide the current Unix timestamp by 30 to get time step $T$. Both compute $\text{HMAC-SHA1}(\text{Secret}, T)$ and truncate it to a 6-digit number. Since both clocks advance at the same rate, both generate the exact same code independently. My server verifies the code with a $\pm 1$ window tolerance to account for clock drift."*

---

### Q6: "Why did you use Spring's `ApplicationEventPublisher` for audit logging instead of saving directly in the controller?"
> *"To adhere to the Single Responsibility Principle and maintain clean separation of concerns. Tightly coupling audit persistence into controllers or services clutters business logic, complicates unit tests, and creates database transaction entanglements. By emitting `SecurityAuditEvent` through Spring's event publisher, core authentication remains clean. A dedicated `@EventListener` handles structured SIEM logging and MySQL persistence independently."*

---

### Q7: "How does your Role Hierarchy work with Spring Security?"
> *"Standard Spring Security checks are flat (`hasRole('USER')` only matches `ROLE_USER`). I registered a `RoleHierarchy` bean defining `ROLE_ADMIN -> ROLE_MANAGER -> ROLE_USER`. This binds to Spring Method Security through `DefaultMethodSecurityExpressionHandler`. As a result, an Administrator can access all endpoints without needing multiple roles assigned in the database, eliminating role duplication and administrative overhead."*

---

### Q8: "Why is the `RateLimitingFilter` positioned before the `JwtAuthenticationFilter`?"
> *"Filter ordering is a critical performance and security concern. JWT validation involves RSA public key cryptographic mathematical calculations. If the rate limiter sat after the JWT filter, an attacker sending 20,000 invalid requests per second would force the server to perform 20,000 RSA computations, maxing out CPU cores. Placing `RateLimitingFilter` first drops abusive traffic in sub-milliseconds before executing cryptographic logic."*

---

### Q9: "What did you do to harden the Docker container for production?"
> *"I implemented a multi-stage Dockerfile:  
> 1. Stage 1 compiles the application using Eclipse Temurin JDK.  
> 2. Stage 2 copies the compiled JAR into a minimal Alpine JRE runtime image (~150MB).  
> 3. Security hardening: The container runs under a non-root user (`appuser:appgroup`) to prevent container breakout exploits.  
> 4. Observability: A `HEALTHCHECK` probe uses Spring Boot Actuator (`/actuator/health`) so Docker/Kubernetes orchestrators know when the service is truly ready to receive traffic."*

---

### Q10: "How do your automated integration tests verify security without real MySQL in CI/CD?"
> *"The project uses an environment-isolated test profile (`src/test/resources/application.yml`). In unit and integration test runs, it spins up an in-memory H2 database running in MySQL compatibility mode (`MODE=MySQL;DB_CLOSE_DELAY=-1`). All 82 integration tests run against the real Spring Security filter chain, testing live HTTP requests via `MockMvc` with CSRF, CORS, Rate Limiting, and JWT filters fully enabled."*

---

## 7. Production Operations & Docker Reference

### Running the Full Cluster:
```bash
# Launch MySQL 8.4 and auth-shield-service in background
docker compose up --build -d

# Verify container status and healthchecks
docker compose ps

# Follow real-time application logs
docker compose logs -f auth-shield-service

# Stop cluster
docker compose down

# Stop and wipe database volume (clean reset)
docker compose down -v
```

### Healthcheck URL:
```bash
curl -k https://localhost:8443/actuator/health
```

### JWKS Public Key URL:
```bash
curl -k https://localhost:8443/.well-known/jwks.json
```

---

## 🏁 Summary Checklist: Your Technological Mastery

You have built:
- [x] **2048-bit RSA Asymmetric Token Signer (RS256)**
- [x] **RFC 7517 Public Key Discovery Endpoint (JWKS)**
- [x] **Hierarchical RBAC (`ADMIN -> MANAGER -> USER`)**
- [x] **Token Bucket Rate Limiting (Bucket4j)**
- [x] **Refresh Token Rotation (RTR) & Replay Theft Detection**
- [x] **Immediate Stateless Token Revocation (Blacklisting)**
- [x] **Account Lockout Guard (5 failures $\rightarrow$ 15 minutes, HTTP 423)**
- [x] **OWASP ASVS Password Policy & Web Security Headers**
- [x] **SIEM Security Audit Event Publishing (SOC 2 Compliant)**
- [x] **RFC 6238 Multi-Factor Authentication (MFA / TOTP) with QR Codes**
- [x] **Hardened Multi-Stage Dockerfile & Docker Compose Orchestration**
- [x] **82 Automated Integration Tests with 100% Pass Rate**
- [x] **100% Automated Postman Test Suite with Built-in Visualizer**

**You are now in total control of this codebase.** Whenever you want to showcase this project or speak about modern security architecture in an interview, this document is your blueprint.
