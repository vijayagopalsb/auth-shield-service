# 🛡️ Enterprise JWT & Multi-Factor Identity Service

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x%20%2F%204.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Security Standard](https://img.shields.io/badge/Security-RS256%20%7C%20OWASP%20ASVS%20%7C%20SOC%202-blue.svg)](https://owasp.org/)
[![Tests](https://img.shields.io/badge/Tests-82%20Passed-success.svg)](#-automated-testing--verification)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage%20Compose-blue.svg)](#-docker-compose-production-setup)

A production-grade, centralized **Identity & Access Management (IAM) Microservice** engineered with Java 21, Spring Boot, Spring Security, and MySQL. Modeled after enterprise identity providers (Auth0, Keycloak), it replaces insecure tutorial JWT patterns with asymmetric RSA-2048 cryptography, RFC 7517 JWKS discovery, Token Bucket rate limiting, Refresh Token Rotation (RTR), Account Lockout, SIEM audit event streams, and RFC 6238 Multi-Factor Authentication (MFA / TOTP).

---

## 📖 Deep Architectural Study & Interview Guide

For a file-by-file codebase walkthrough, request lifecycle traces, and **10 Senior Architect interview questions with model answers**, see:
👉 **[The Complete Project Masterclass Guide (`docs/PROJECT_MASTERCLASS.md`)](docs/PROJECT_MASTERCLASS.md)**

---

## 🏛️ System Architecture: The 4 Defense Perimeters

Every incoming HTTP request must traverse **4 strict defensive perimeters** before accessing data:

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
  PERIMETER 4: BUSINESS LOGIC, MFA & AUDIT            │
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

## 🛡️ The 10 Production Security Pillars

| # | Security Pillar | Implementation Class | Threat Model Addressed |
| :-: | :--- | :--- | :--- |
| **1** | **RS256 Asymmetric Cryptography** | `RsaJwtService.java` | Prevents key compromise across microservices. Signs tokens with 2048-bit RSA Private Key; downstream services verify via Public Key only. |
| **2** | **RFC 7517 JWKS Discovery** | `JwksController.java` | Exposes `/.well-known/jwks.json` so downstream gateways and services fetch and cache the Public Key automatically with zero database coupling. |
| **3** | **Hierarchical RBAC** | `SecurityConfig.java` | Implements `ADMIN -> MANAGER -> USER` role hierarchy. Administrators automatically inherit all subordinate permissions without database role bloat. |
| **4** | **Bucket4j Token Bucket Limiting** | `RateLimitingFilter.java` | Positioned at the very front of the servlet chain to drop DoS floods and bot surges (5 req/min auth, 30 req/min general) before executing expensive cryptography. |
| **5** | **Refresh Token Rotation (RTR)** | `RefreshTokenService.java` | Every refresh issues a new refresh token and revokes the old one. If an already-revoked token is replayed, **all sessions for the user are immediately revoked**. |
| **6** | **Stateless Token Blacklisting** | `TokenBlacklistService.java` | Resolves the classic stateless JWT logout limitation by tracking revoked JWT IDs (`jti`) in an in-memory TTL cache until expiration. |
| **7** | **Account Lockout Guard** | `UserService.java` | Halts distributed brute-force attacks across rotating botnet IPs: 5 consecutive failed passwords locks the account for 15 minutes (**HTTP 423 Locked**). |
| **8** | **OWASP ASVS Web Hardening** | `RegisterRequest.java` | Enforces Level 2 ASVS strong passwords (8+ chars, upper, lower, digits, symbols), strict CORS policies, and security headers (HSTS, CSP, X-Frame-Options DENY). |
| **9** | **SIEM Audit Event Publishing** | `SecurityAuditEventListener.java` | Decoupled event streams via Spring `ApplicationEventPublisher`. Emits structured logs for ELK/Splunk and persists immutable records to MySQL (`security_audit_logs`). |
| **10**| **MFA / TOTP (RFC 6238)** | `TotpService.java` | 100% free, offline Two-Factor Authentication compatible with Google Authenticator. Two-step login interrupts password auth with restricted 5-minute temporary tokens. |

---

## 🎬 4 Real-World Attack Scenarios & Mitigations

### Scenario 1: Botnet DoS Flood
- **Attack:** An automated script floods `/api/auth/login` with 500 requests/second from an IP address.
- **Defense:** `RateLimitingFilter` intercepts traffic at **Perimeter 1**. The client's bucket exhausts its 5 tokens, and subsequent requests are rejected immediately with **HTTP 429 Too Many Requests** without touching Spring Security, RSA crypto, or the database.

### Scenario 2: Distributed Password Guessing (Brute-Force)
- **Attack:** An attacker uses 5,000 rotating proxy IPs to test passwords against account `alice`, bypassing IP rate limits.
- **Defense:** `UserService` tracks failures at the **account level**. On the 5th bad attempt across any IP, the account is locked for 15 minutes, returning **HTTP 423 Locked** and firing an `ACCOUNT_LOCKED` security alert.

### Scenario 3: Stolen Refresh Token Replay (Token Theft)
- **Attack:** An attacker intercepts a legitimate user's already-used refresh token from browser storage and attempts to call `/api/auth/refresh`.
- **Defense:** `RefreshTokenService` detects that the presented token is already marked `revoked = true`. It immediately triggers `revokeAllByUser(user)`, revoking every active session belonging to that user, neutralizing the theft, and returning **HTTP 403 Forbidden**.

### Scenario 4: Phished Credentials (MFA Defense)
- **Attack:** An attacker obtains a user's raw password via a phishing email and attempts to log in.
- **Defense:** Primary login succeeds with username/password, but because `mfaEnabled == true`, the server **refuses** to issue full tokens. It returns `mfaRequired: true` and a restricted `MFA_TEMP` token. The attacker cannot access any protected API without the rotating 6-digit TOTP code generated on the victim's physical device.

---

## 🐳 Docker Compose Production Setup

Run the full production stack (hardened Spring Boot container + MySQL 8.4) with a single command:

```bash
docker compose up --build -d
```

### Container Architecture & Hardening:
- **Multi-Stage Build:** Builder compiles using JDK 21; Runner packages a minimal Alpine JRE (~150MB).
- **Non-Root Execution:** Runs under `appuser:appgroup` to prevent container breakout exploits.
- **Healthcheck Synchronization:** The auth service waits for MySQL to become healthy (`mysqladmin ping`) before starting.
- **Container Healthcheck:** Uses Spring Boot Actuator (`https://localhost:8443/actuator/health`).

### Host Port Mapping:
- **`jwt-service-demo`:** `https://localhost:8443` (Enforces TLS 1.3 encryption).
- **`jwt-mysql`:** `localhost:3308` (Mapped to 3308 to avoid conflicts with any local MySQL on 3306).

To stop the containers:
```bash
docker compose down
```

To stop and reset database data:
```bash
docker compose down -v
```

---

## 💻 Local Development & Automated Tests

### Prerequisites:
- Java 21 JDK
- Maven 3.9+ (or use bundled `./mvnw`)

### Running All 82 Automated Integration Tests:
```bash
# Windows
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```
*All 82 tests run against an in-memory database with full Spring Security filters enabled.*

### Running the App Locally:
```bash
.\mvnw.cmd spring-boot:run
```

---

## 📮 Testing with Postman (100% Automated)

A complete, pre-configured Postman Collection is included in the repository:
👉 **[`docs/jwt-service-demo.postman_collection.json`](docs/jwt-service-demo.postman_collection.json)**

### Postman Testing Features:
1. **QR Code Visualizer:** Click the **Visualize** tab on the `MFA Setup` response to view and scan the real QR code with Google Authenticator!
2. **Built-in CryptoJS TOTP Generator:** The collection automatically calculates the RFC 6238 6-digit code in a Pre-request Script—**no phone required** to run tests!

---

## 📡 Complete REST API Reference

### Public Endpoints
| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/.well-known/jwks.json` | RFC 7517 JSON Web Key Set (RSA Public Key) |
| `POST` | `/api/auth/register` | Register new user account with ASVS password policy |
| `POST` | `/api/auth/login` | Primary login (returns JWT or `mfaRequired: true`) |
| `POST` | `/api/auth/mfa/verify-login` | Step 2 login: validates temp token + 6-digit TOTP code |
| `POST` | `/api/auth/refresh` | Rotate refresh token (RTR) and issue fresh access token |
| `POST` | `/api/auth/logout` | Revoke refresh token and blacklist access token |
| `GET` | `/actuator/health` | Container liveness and readiness probe |

### Protected Endpoints
| Method | Path | Required Role | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/auth/profile` | Any (`USER`) | View current authenticated profile |
| `POST` | `/api/auth/mfa/setup` | Any (`USER`) | Generate Base32 secret & QR code for MFA enrollment |
| `POST` | `/api/auth/mfa/enable` | Any (`USER`) | Confirm initial 6-digit code and activate MFA |
| `POST` | `/api/auth/mfa/disable` | Any (`USER`) | Provide 6-digit code and deactivate MFA |
| `PUT` | `/api/auth/users/{username}/role` | `ADMIN` | Elevate or change user role |
| `GET` | `/api/auth/audit-logs` | `ADMIN` | Query recent 50 SIEM security audit logs |
| `GET` | `/api/demo/admin-only` | `ADMIN` | Hierarchical RBAC demo (Admin only) |
| `GET` | `/api/demo/manager-area` | `MANAGER` | Hierarchical RBAC demo (Manager & Admin) |
| `GET` | `/api/demo/user-area` | `USER` | Hierarchical RBAC demo (User, Manager & Admin) |

---

## 🔮 Project Roadmap & Changelog

### Version 1.0.0 (Current Release)
- ✅ 2048-bit Asymmetric RSA Cryptography (RS256) & RFC 7517 JWKS.
- ✅ Hierarchical RBAC (`ADMIN -> MANAGER -> USER`).
- ✅ Bucket4j Token Bucket Rate Limiting Filter.
- ✅ Refresh Token Rotation (RTR) & Replay Theft Detection.
- ✅ Instant Stateless Access Token Blacklisting on Logout.
- ✅ Account Lockout Guard (5 failed attempts $\rightarrow$ 15-minute lock).
- ✅ OWASP ASVS Password Policy, strict CORS, and security headers.
- ✅ SIEM Security Audit Event Publishing & MySQL log persistence.
- ✅ RFC 6238 Multi-Factor Authentication (MFA / TOTP) with QR codes.
- ✅ Multi-stage Dockerfile and Docker Compose orchestration.
- ✅ 82 Automated Integration Tests with 100% pass rate.

### Version 1.1.0 (Planned)
- 📌 **Kafka Audit Event Streaming:** Publish `SecurityAuditEvent` to an Apache Kafka topic for distributed log ingestion into ELK Stack.
- 📌 **Distributed Redis Rate Limiting:** Migrate in-memory Bucket4j cache to Redis for multi-pod Kubernetes horizontal scaling.

### Version 1.2.0 (Planned)
- 📌 **OAuth2 Authorization Server:** Full OAuth2 Authorization Code Flow with PKCE for mobile and Single Page Applications.

---

## 📄 License
This project is open-source under the MIT License.
