# 🛡️ Enterprise JWT & Multi-Factor Identity Service

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x%20%2F%204.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Security Standard](https://img.shields.io/badge/Security-RS256%20%7C%20OWASP%20ASVS%20%7C%20SOC%202-blue.svg)](https://owasp.org/)
[![Tests](https://img.shields.io/badge/Tests-82%20Passed-success.svg)](#-automated-testing--verification)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage%20Compose-blue.svg)](#-docker-compose-production-setup)

A production-grade, centralized **Identity & Access Management (IAM) Service** built with Spring Boot, Spring Security, and MySQL. Implements asymmetric RSA cryptography, RFC 7517 JWKS endpoints, Token Bucket rate limiting, Refresh Token Rotation (RTR), Account Lockout, SIEM audit event publishing, and RFC 6238 Multi-Factor Authentication (MFA / TOTP).

---

## 🏛️ Architectural Overview

```
                           ┌────────────────────────────────────────────────────────┐
                           │                   Incoming HTTP Request                │
                           └────────────────────────────────────────────────────────┘
                                                       │
  LAYER 1: DoS & Bot Shield                            ▼
  (Bucket4j Token Bucket)                 ┌────────────────────────┐
                                          │   RateLimitingFilter   │─── Limit Exceeded ──► [HTTP 429 Too Many Requests]
                                          └────────────────────────┘
                                                       │
  LAYER 2: Cryptographic Auth                          ▼
  (RS256 Asymmetric RSA)                  ┌────────────────────────┐
                                          │ JwtAuthenticationFilter│─── Blacklisted / MFA Temp ──► [Filter Pass / Anonymous]
                                          └────────────────────────┘
                                                       │
  LAYER 3: Hierarchical RBAC                           ▼
  (ADMIN > MANAGER > USER)                ┌────────────────────────┐
                                          │   SecurityFilterChain  │─── Insufficient Role ──► [HTTP 403 Forbidden]
                                          │     + @PreAuthorize    │─── Unauthenticated   ──► [HTTP 401 Unauthorized]
                                          └────────────────────────┘
                                                       │
  LAYER 4: Business Logic & Audit                      ▼
  (Controllers, Services & MySQL)         ┌────────────────────────┐
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

## 🚀 Key Security Features

### 1. 🔑 Asymmetric RS256 Cryptography & RFC 7517 JWKS
- **Private Key Signing:** Tokens are signed using a 2048-bit RSA Private Key. Only this service holds the private key.
- **RFC 7517 JWKS Endpoint (`GET /.well-known/jwks.json`):** Downstream microservices download the public key in standard JSON format to verify token signatures offline without accessing this database.

### 2. 🔄 Refresh Token Rotation (RTR) & Replay Detection
- Every refresh operation revokes the presented refresh token and issues a fresh one, recording lineage (`replacedByToken`).
- **Automatic Breach Alarm:** If an attacker attempts to reuse an already-revoked refresh token, the service detects a token theft attack, invalidates **all active sessions** for that user, and fires a `TOKEN_REUSE_BREACH` security event.

### 3. 🚫 Instant Access Token Blacklisting on Logout
- Solves the classic stateless JWT revocation limitation.
- Upon logout, access token IDs (JTIs) are blacklisted in-memory until their natural expiration, instantly terminating compromised sessions.

### 4. 🛡️ Bucket4j Token Bucket Rate Limiting
- Sits before Spring Security in the filter chain to prevent CPU starvation and DoS attacks.
- Configurable per-IP rate limits: **5 requests/minute** for sensitive authentication endpoints, **30 requests/minute** for general APIs.

### 5. 🔒 Account Lockout Protection (Brute-Force Guard)
- Enforces an automated lockout policy: 5 consecutive invalid passwords triggers a **15-minute account lockout**.
- Returns **HTTP 423 Locked** with exact remaining minutes. Automatically unlocks after the lockout duration elapses.

### 6. 🌐 OWASP Web Hardening & ASVS Password Policy
- **HTTP Security Headers:** Strict Transport Security (HSTS), Content-Security-Policy (`default-src 'self'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.
- **CORS:** Environment-driven cross-origin whitelist with preflight caching (`maxAge: 3600`).
- **OWASP ASVS Strong Password Validation:** Minimum 8 characters, requiring uppercase, lowercase, numbers, and special symbols.

### 7. 📜 SIEM Security Audit Logging (SOC 2 / ISO 27001)
- Uses Spring Framework's decoupled `ApplicationEventPublisher`.
- Emits structured log lines (`[SECURITY_AUDIT] event_type=...`) parsable by Logstash, Elasticsearch, and Splunk.
- Persists audit records to `security_audit_logs` MySQL table. Admin-only monitoring via `GET /api/auth/audit-logs`.

### 8. 📱 Multi-Factor Authentication (MFA / TOTP RFC 6238)
- 100% free and offline standard compatible with **Google Authenticator**, **Microsoft Authenticator**, and **2FAS**.
- Generates 160-bit Base32 secret keys and QR code data URLs (Base64 PNG via ZXing).
- 2-step login: Step 1 returns `mfaRequired: true` with a restricted 5-minute temporary token; Step 2 validates the 6-digit TOTP code before issuing full RS256 access tokens.

---

## 🐳 Docker Compose Production Setup

Run the entire stack (MySQL 8.4 database + hardened Spring Boot container) with a single command:

```bash
docker compose up --build -d
```

### Container Features:
- **Multi-stage Docker build:** Slim Alpine JRE runtime image (~150MB).
- **Non-root user execution (`appuser:appgroup`):** Hardened against container breakouts.
- **Container healthcheck:** Uses Spring Boot Actuator (`/actuator/health`).
- **Graceful dependency:** The auth service waits for MySQL to be fully healthy before starting.

To stop the cluster:
```bash
docker compose down
```

---

## 💻 Local Development & Automated Tests

### Prerequisites:
- Java 21 JDK
- Maven 3.9+ (or use included `./mvnw`)
- MySQL 8.0+ running on `localhost:3306` (or in-memory H2 used automatically in tests)

### Running All 82 Automated Integration Tests:
```bash
# Windows
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```

### Running the Application Locally:
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

The application runs on `https://localhost:8443` (TLS 1.3 enforced with bundled PKCS#12 keystore).

---

## 📮 Testing with Postman (100% Automated)

A complete, pre-configured Postman Collection is provided:
👉 **[`docs/jwt-service-demo.postman_collection.json`](docs/jwt-service-demo.postman_collection.json)**

### Postman Features Included:
1. **Postman Visualizer QR Code:** View the **"Step A: Setup MFA"** response under the **Visualize** tab to see and scan the real QR code with Google Authenticator!
2. **Built-in TOTP Generator Script:** The collection features a Pre-request Script using Postman's built-in `CryptoJS` to automatically calculate the RFC 6238 6-digit code—**no phone required**!

---

## 📡 API Reference

### Public Endpoints
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/.well-known/jwks.json` | RFC 7517 JSON Web Key Set (Public Key) |
| `POST` | `/api/auth/register` | Register new user account |
| `POST` | `/api/auth/login` | Primary login (returns JWT or `mfaRequired: true`) |
| `POST` | `/api/auth/mfa/verify-login`| Step 2 login: validates temp token + 6-digit TOTP code |
| `POST` | `/api/auth/refresh` | Rotate refresh token (RTR) and issue new access token |
| `POST` | `/api/auth/logout` | Revoke refresh token and blacklist access token |
| `GET` | `/actuator/health` | Container liveness and readiness probe |

### Authenticated Endpoints
| Method | Endpoint | Required Role | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/auth/profile` | Any (`USER`) | View current authenticated user profile |
| `POST` | `/api/auth/mfa/setup` | Any (`USER`) | Generate Base32 secret & QR code for MFA |
| `POST` | `/api/auth/mfa/enable` | Any (`USER`) | Confirm 6-digit code and activate MFA |
| `POST` | `/api/auth/mfa/disable` | Any (`USER`) | Provide 6-digit code and deactivate MFA |
| `PUT` | `/api/auth/users/{username}/role` | `ADMIN` | Elevate or change user role |
| `GET` | `/api/auth/audit-logs` | `ADMIN` | Query recent 50 SIEM security audit logs |
| `GET` | `/api/demo/admin-only` | `ADMIN` | Hierarchical RBAC demo (Admin only) |
| `GET` | `/api/demo/manager-area`| `MANAGER` (or `ADMIN`) | Hierarchical RBAC demo |
| `GET` | `/api/demo/user-area` | `USER` (or `MANAGER`, `ADMIN`) | Hierarchical RBAC demo |

---

## 📄 License
This project is open-source under the MIT License.
