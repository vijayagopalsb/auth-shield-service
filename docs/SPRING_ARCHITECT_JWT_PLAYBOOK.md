# Spring Architect's JWT Playbook: Production Blueprint & Enterprise Patterns

> **Role Focus**: Lead Spring Architect / Principal Engineer  
> **Target Framework**: Spring Boot 3.x / 4.x, Spring Security 6.x, Java 21  
> **Companion Document**: [`JWT_MASTERY_GUIDE.md`](./JWT_MASTERY_GUIDE.md)

---

## Table of Contents
1. [Architectural Decision Records (ADRs)](#1-architectural-decision-records-adrs)
   - [ADR 1: Custom JJWT Filter vs. Spring OAuth2 Resource Server](#adr-1-custom-jjwt-filter-vs-spring-oauth2-resource-server)
   - [ADR 2: Identity Provider Topology (Spring Auth Server vs. Keycloak/Okta)](#adr-2-identity-provider-topology)
   - [ADR 3: Edge Gateway Termination vs. End-to-End Token Relay](#adr-3-edge-gateway-termination-vs-end-to-end-token-relay)
2. [Production Security Filter Chain Configuration](#2-production-security-filter-chain-configuration)
   - [Stateless SecurityFilterChain Bean](#21-stateless-securityfilterchain-bean)
   - [RFC 7807 ProblemDetail Exception Handling (401 & 403)](#22-rfc-7807-problemdetail-exception-handling)
   - [Enterprise CORS Policy Architecture](#23-enterprise-cors-policy-architecture)
3. [Authority & Role Mapping Architecture](#3-authority--role-mapping-architecture)
   - [The `ROLE_` Prefix Trap & Solution](#31-the-role_-prefix-trap--solution)
   - [Custom Claims-to-GrantedAuthorities Converter](#32-custom-claims-to-grantedauthorities-converter)
   - [Role Hierarchy Configuration (`RoleHierarchy`)](#33-role-hierarchy-configuration-rolehierarchy)
4. [Enterprise Microservices Token Relay & Inter-Service Security](#4-enterprise-microservices-token-relay--inter-service-security)
   - [Spring Cloud Gateway JWT Validation & Header Injection](#41-spring-cloud-gateway-jwt-validation-filter)
   - [Downstream Token Propagation via `RestClient`](#42-downstream-token-propagation-via-restclient)
   - [OpenFeign RequestInterceptor Token Relay](#43-openfeign-requestinterceptor-token-relay)
5. [Distributed Revocation via Spring Data Redis](#5-distributed-revocation-via-spring-data-redis)
   - [Custom `OAuth2TokenValidator<Jwt>` with Redis Denylist](#51-custom-oauth2tokenvalidatorjwt-with-redis-denylist)
6. [Multi-Tenant JWT Architecture](#6-multi-tenant-jwt-architecture)
   - [Dynamic Multi-Issuer Resolution (`AuthenticationManagerResolver`)](#61-dynamic-multi-issuer-resolution)
   - [ThreadLocal / ScopedValue TenantContext Propagation](#62-threadlocal--scopedvalue-tenantcontext-propagation)
7. [Observability, MDC Logging & Tracing](#7-observability-mdc-logging--tracing)
   - [MDC Security Context Filter (User & Tenant in Logs)](#71-mdc-security-context-filter)
8. [Testing Architecture for Spring Security](#8-testing-architecture-for-spring-security)
   - [Slice Testing with `@WebMvcTest` and `jwt()`](#81-slice-testing-with-webmvctest-and-jwt)
   - [Meta-Annotation: `@WithMockAdminUser`](#82-meta-annotation-withmockadminuser)

---

## 1. Architectural Decision Records (ADRs)

### ADR 1: Custom JJWT Filter vs. Spring OAuth2 Resource Server

```
+--------------------------------------------------------------------------------------------------+
|                                    DECISION TREE FOR SPRING ARCHITECTS                            |
+--------------------------------------------------------------------------------------------------+
|                                                                                                  |
|   Are you connecting to an OpenID Connect / OAuth2 IdP (Keycloak, Auth0, Okta, Azure AD)?        |
|                                                                                                  |
|             YES                                                       NO                         |
|              |                                                         |                         |
|              v                                                         v                         |
|   Use `spring-boot-starter-oauth2-resource-server`        Use JJWT 0.12.x Custom Filter          |
|   - Out-of-the-box JWKS caching                           - Microservices internal cluster       |
|   - Automatic Key Rotation                                - Lightweight monoliths                |
|   - Standard Nimbus JOSE parser                           - Ultra-low latency requirements       |
|   - Standard OIDC claim validation                        - Tailored custom token structures     |
|                                                                                                  |
+--------------------------------------------------------------------------------------------------+
```

* **Verdict**: For external identity integration or standard microservices consuming tokens from an IdP, prefer `spring-boot-starter-oauth2-resource-server`. For custom self-issued internal tokens where minimal dependency footprint and exact control over signing algorithms (e.g. Ed25519) is required, use JJWT 0.12.x.

---

### ADR 2: Identity Provider Topology
* **Spring Authorization Server (SAS)**: Best when you need full control over user schemas, embedded auth in an existing database, or zero external licensing costs.
* **Keycloak / Okta / Auth0**: Best when you require turnkey features: MFA/WebAuthn, social logins (Google/GitHub), identity federation (SAML/LDAP), self-service password reset, and compliance audits (SOC2, ISO27001).

---

### ADR 3: Edge Gateway Termination vs. End-to-End Token Relay
* **Architect Recommendation (Hybrid Approach)**:
  1. **At API Gateway**: Validate incoming client JWT against JWKS; reject expired/malformed tokens immediately.
  2. **To Downstream Services**: Propagate the verified token via `Authorization: Bearer <jwt>`. Downstream services verify tokens locally using cached public keys.
  3. **Internal Headers**: Enrich requests with `X-User-Id` and `X-Tenant-Id` for quick logging/metrics, but **always base authorization rules on the cryptographically validated token context**, never on headers alone.

---

## 2. Production Security Filter Chain Configuration

In Spring Security 6 (Spring Boot 3+), all configuration is declared via component-based beans using the Lambda DSL.

### 2.1 Stateless `SecurityFilterChain` Bean

```java
package com.example.jwtservicedemo.config;

import com.example.jwtservicedemo.security.Http401UnauthorizedEntryPoint;
import com.example.jwtservicedemo.security.Http403AccessDeniedHandler;
import com.example.jwtservicedemo.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final CorsConfigurationSource corsConfigurationSource;
    private final Http401UnauthorizedEntryPoint unauthorizedEntryPoint;
    private final Http403AccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthFilter,
            AuthenticationProvider authenticationProvider,
            CorsConfigurationSource corsConfigurationSource,
            Http401UnauthorizedEntryPoint unauthorizedEntryPoint,
            Http403AccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationProvider = authenticationProvider;
        this.corsConfigurationSource = corsConfigurationSource;
        this.unauthorizedEntryPoint = unauthorizedEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            // 1. Disable CSRF for stateless REST APIs using JWT in headers
            .csrf(AbstractHttpConfigurer::disable)
            
            // 2. Attach strict CORS configuration
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            
            // 3. Make session management purely STATELESS (No JSESSIONID)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // 4. Attach enterprise exception handlers emitting RFC 7807 ProblemDetails
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(unauthorizedEntryPoint) // 401
                .accessDeniedHandler(accessDeniedHandler)         // 403
            )
            
            // 5. URL authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                
                // Allow OPTIONS preflight calls for CORS
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Sensitive actuator endpoints require ADMIN
                .requestMatchers("/actuator/**").hasRole("SYSTEM_ADMIN")
                
                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            
            // 6. Register authentication provider & JWT filter
            .authenticationProvider(authenticationProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

---

### 2.2 RFC 7807 `ProblemDetail` Exception Handling

Never leak raw stack traces or return default Tomcat HTML error pages. Spring Boot 3 natively supports **RFC 7807 (Problem Details for HTTP APIs)**.

#### 401 Unauthorized Entry Point:
```java
package com.example.jwtservicedemo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Component
public class Http401UnauthorizedEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public Http401UnauthorizedEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication credentials are missing, invalid, or expired."
        );
        problemDetail.setType(URI.create("https://api.example.com/errors/unauthorized"));
        problemDetail.setTitle("Unauthorized");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("error_code", "AUTH_TOKEN_INVALID");

        response.getWriter().write(objectMapper.writeValueAsString(problemDetail));
    }
}
```

#### 403 Forbidden Access Denied Handler:
```java
package com.example.jwtservicedemo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Component
public class Http403AccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public Http403AccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "You do not possess the required permissions to access this resource."
        );
        problemDetail.setType(URI.create("https://api.example.com/errors/forbidden"));
        problemDetail.setTitle("Forbidden");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("error_code", "INSUFFICIENT_PERMISSIONS");

        response.getWriter().write(objectMapper.writeValueAsString(problemDetail));
    }
}
```

---

### 2.3 Enterprise CORS Policy Architecture

Common bug in Spring Security: CORS preflight `OPTIONS` requests fail with 401 because they don't contain the `Authorization` header.

```java
package com.example.jwtservicedemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${application.cors.allowed-origins:https://app.example.com,https://admin.example.com}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Explicitly set allowed origins (Never use "*" in production when credentials are true)
        configuration.setAllowedOrigins(allowedOrigins);
        
        // Allowed HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        
        // Allowed request headers
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Requested-With",
                "X-Tenant-Id"
        ));
        
        // Headers exposed to browser JS
        configuration.setExposedHeaders(Arrays.asList("Authorization", "X-Total-Count"));
        
        // Allow cookies / Authorization header
        configuration.setAllowCredentials(true);
        
        // Cache preflight OPTIONS response for 1 hour to reduce network overhead
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

---

## 3. Authority & Role Mapping Architecture

### 3.1 The `ROLE_` Prefix Trap & Solution
Spring Security distinguishes between:
* **Roles**: Evaluated with `.hasRole("ADMIN")` $\implies$ Spring internally checks for `ROLE_ADMIN`.
* **Authorities / Scopes**: Evaluated with `.hasAuthority("SCOPE_read")` $\implies$ Spring checks exact literal match `SCOPE_read`.

If your JWT contains `"roles": ["ADMIN"]`, calling `.hasRole("ADMIN")` will fail unless the converter prefixes the string with `ROLE_`.

### 3.2 Custom Claims-to-GrantedAuthorities Converter

When using `spring-boot-starter-oauth2-resource-server`, use this converter to extract nested roles and scopes from complex IdP payloads (such as Keycloak's `realm_access.roles`):

```java
package com.example.jwtservicedemo.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter defaultAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        Collection<GrantedAuthority> authorities = Stream.concat(
                extractDefaultAuthorities(jwt).stream(),
                extractRealmRoles(jwt).stream()
        ).collect(Collectors.toSet());

        return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"));
    }

    private Collection<GrantedAuthority> extractDefaultAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = defaultAuthoritiesConverter.convert(jwt);
        return authorities != null ? authorities : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !realmAccess.containsKey("roles")) {
            return Collections.emptyList();
        }

        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .collect(Collectors.toList());
    }
}
```

### 3.3 Role Hierarchy Configuration (`RoleHierarchy`)
Avoid bloating method security with `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")`. Configure a clean role hierarchy:

```java
package com.example.jwtservicedemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

@Configuration
public class RoleHierarchyConfig {

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
            ROLE_SUPER_ADMIN > ROLE_ADMIN
            ROLE_ADMIN > ROLE_MANAGER
            ROLE_MANAGER > ROLE_USER
            ROLE_USER > ROLE_GUEST
        """);
    }
}
```

Now, checking `@PreAuthorize("hasRole('USER')")` automatically evaluates to `true` for `MANAGER`, `ADMIN`, and `SUPER_ADMIN`.

---

## 4. Enterprise Microservices Token Relay & Inter-Service Security

```
+--------------------------------------------------------------------------------------------------+
|                                    TOKEN PROPAGATION TOPOLOGY                                    |
+--------------------------------------------------------------------------------------------------+
|                                                                                                  |
|   Client ----> [ Spring Cloud Gateway ]                                                          |
|                       | (Validates JWT, injects X-User-Id, X-Tenant-Id)                           |
|                       v                                                                          |
|                [ Order Microservice ]                                                            |
|                       |                                                                          |
|                       | RestClient / OpenFeign with TokenRelayInterceptor                        |
|                       | propagates original Authorization: Bearer <jwt>                          |
|                       v                                                                          |
|                [ Payment Microservice ]                                                          |
|                                                                                                  |
+--------------------------------------------------------------------------------------------------+
```

### 4.1 Spring Cloud Gateway JWT Validation Filter

```java
package com.example.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtHeaderEnrichmentGatewayFilterFactory 
        extends AbstractGatewayFilterFactory<JwtHeaderEnrichmentGatewayFilterFactory.Config> {

    private final ReactiveJwtDecoder jwtDecoder;

    public JwtHeaderEnrichmentGatewayFilterFactory(ReactiveJwtDecoder jwtDecoder) {
        super(Config.class);
        this.jwtDecoder = jwtDecoder;
    }

    public static class Config {}

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            return jwtDecoder.decode(token)
                    .flatMap(jwt -> {
                        // Extract claims and enrich internal headers
                        String userId = jwt.getSubject();
                        String tenantId = jwt.getClaimAsString("tenant_id");

                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header("X-User-Id", userId)
                                .header("X-Tenant-Id", tenantId != null ? tenantId : "default")
                                .build();

                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    })
                    .onErrorResume(e -> onError(exchange, HttpStatus.UNAUTHORIZED));
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
```

---

### 4.2 Downstream Token Propagation via `RestClient`
In Spring Boot 3.2+, `RestClient` is the modern synchronous client replacing `RestTemplate`.

```java
package com.example.jwtservicedemo.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

                    // If current context is an authenticated JWT, propagate token downstream
                    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                        String tokenValue = jwtAuth.getToken().getTokenValue();
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue);
                    }

                    return execution.execute(request, body);
                })
                .build();
    }
}
```

---

### 4.3 OpenFeign `RequestInterceptor` Token Relay

```java
package com.example.jwtservicedemo.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class FeignTokenRelayInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String tokenValue = jwtAuth.getToken().getTokenValue();
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue);
        }
    }
}
```

---

## 5. Distributed Revocation via Spring Data Redis

### 5.1 Custom `OAuth2TokenValidator<Jwt>` with Redis Denylist

Spring Security's OAuth2 Resource Server allows chaining custom validators directly into the `JwtDecoder`.

```java
package com.example.jwtservicedemo.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final String REVOCATION_PREFIX = "token:denylist:";
    private final StringRedisTemplate redisTemplate;

    public RedisTokenRevocationValidator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();

        // 1. Check if token JTI is in Redis Denylist
        if (jti != null) {
            Boolean isRevoked = redisTemplate.hasKey(REVOCATION_PREFIX + jti);
            if (Boolean.TRUE.equals(isRevoked)) {
                OAuth2Error error = new OAuth2Error(
                        "token_revoked",
                        "The access token has been explicitly revoked.",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }
        }

        // 2. Check user-level revocation epoch (Logout from all devices)
        String userId = jwt.getSubject();
        String userEpochKey = "user:epoch:" + userId;
        String minValidTimestamp = redisTemplate.opsForValue().get(userEpochKey);

        if (minValidTimestamp != null && jwt.getIssuedAt() != null) {
            long epochTime = Long.parseLong(minValidTimestamp);
            if (jwt.getIssuedAt().getEpochSecond() < epochTime) {
                OAuth2Error error = new OAuth2Error(
                        "session_terminated",
                        "User sessions were terminated. Please log in again.",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }
        }

        return OAuth2TokenValidatorResult.success();
    }
}
```

#### Registering the Validator in `JwtDecoder`:
```java
@Bean
public JwtDecoder jwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        RedisTokenRevocationValidator redisRevocationValidator) {

    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

    OAuth2TokenValidator<Jwt> defaultValidators = JwtValidators.createDefault();
    OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
            defaultValidators,
            redisRevocationValidator
    );

    jwtDecoder.setJwtValidator(combinedValidator);
    return jwtDecoder;
}
```

---

## 6. Multi-Tenant JWT Architecture

### 6.1 Dynamic Multi-Issuer Resolution (`AuthenticationManagerResolver`)

In B2B SaaS, each tenant often connects their own identity provider (e.g. Tenant A $\implies$ `https://tenant-a.okta.com`, Tenant B $\implies$ `https://auth.tenant-b.com`).

```java
package com.example.jwtservicedemo.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class MultiTenantSecurityConfig {

    private final Map<String, AuthenticationManager> authenticationManagers = new ConcurrentHashMap<>();

    @Bean
    public SecurityFilterChain multiTenantSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(tenantAuthenticationManagerResolver())
            )
            .build();
    }

    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> tenantAuthenticationManagerResolver() {
        return request -> {
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId == null) {
                tenantId = "default";
            }

            return authenticationManagers.computeIfAbsent(tenantId, this::createTenantAuthManager);
        };
    }

    private AuthenticationManager createTenantAuthManager(String tenantId) {
        String issuerUri = resolveIssuerForTenant(tenantId);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(JwtDecoders.fromIssuerLocation(issuerUri));
        return provider::authenticate;
    }

    private String resolveIssuerForTenant(String tenantId) {
        // Look up registered tenant identity provider from database / Vault
        return switch (tenantId) {
            case "acme" -> "https://acme.auth0.com/";
            case "globex" -> "https://globex.okta.com/oauth2/default";
            default -> "https://auth.example.com/";
        };
    }
}
```

---

### 6.2 ThreadLocal / ScopedValue TenantContext Propagation

In Java 21, `ScopedValue` provides high-performance, immutable context propagation ideal for Virtual Threads.

```java
package com.example.jwtservicedemo.context;

public class TenantContext {

    public static final ScopedValue<String> CURRENT_TENANT = ScopedValue.newInstance();

    // Fallback for standard thread pools
    private static final ThreadLocal<String> THREAD_LOCAL_TENANT = new ThreadLocal<>();

    public static void setTenantId(String tenantId) {
        THREAD_LOCAL_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        if (CURRENT_TENANT.isBound()) {
            return CURRENT_TENANT.get();
        }
        return THREAD_LOCAL_TENANT.get();
    }

    public static void clear() {
        THREAD_LOCAL_TENANT.remove();
    }
}
```

---

## 7. Observability, MDC Logging & Tracing

### 7.1 MDC Security Context Filter

Ensure every log line in production automatically contains the `userId` and `tenantId` from the verified JWT.

```java
package com.example.jwtservicedemo.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class MdcSecurityContextFilter extends OncePerRequestFilter {

    private static final String MDC_USER_ID = "userId";
    private static final String MDC_TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                MDC.put(MDC_USER_ID, jwt.getSubject());
                
                String tenant = jwt.getClaimAsString("tenant_id");
                if (tenant != null) {
                    MDC.put(MDC_TENANT_ID, tenant);
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // Prevent MDC context leaking to pooled threads
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_TENANT_ID);
        }
    }
}
```

#### Logback Configuration (`logback-spring.xml`):
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [user=%X{userId:-anon}, tenant=%X{tenantId:-none}] %logger{36} - %msg%n</pattern>
```

---

## 8. Testing Architecture for Spring Security

### 8.1 Slice Testing with `@WebMvcTest` and `jwt()`

Do not spin up real databases or mock filters manually. Use Spring Security Test's fluent `jwt()` RequestPostProcessor:

```java
package com.example.jwtservicedemo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should return 401 Unauthorized when unauthenticated")
    void shouldReturn401WhenAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/orders/123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when user lacks ROLE_ADMIN")
    void shouldReturn403WhenLacksRole() throws Exception {
        mockMvc.perform(get("/api/v1/orders/admin-metrics")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 200 OK with custom JWT claims")
    void shouldReturn200WithAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/orders/admin-metrics")
                .with(jwt()
                        .jwt(builder -> builder
                                .subject("admin_user_99")
                                .claim("tenant_id", "tenant_acme"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
}
```

---

### 8.2 Meta-Annotation: `@WithMockAdminUser`

Avoid repeating authorities in test classes by creating reusable security meta-annotations:

```java
package com.example.jwtservicedemo.test;

import org.springframework.security.test.context.support.WithMockUser;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@WithMockUser(username = "admin_super", roles = {"ADMIN", "SYSTEM_ADMIN"})
public @interface WithMockAdminUser {
}
```

Usage in unit and integration tests:
```java
@Test
@WithMockAdminUser
void executeRestrictedOperation_ShouldSucceed() throws Exception {
    mockMvc.perform(post("/api/v1/system/reindex"))
            .andExpect(status().isOk());
}
```
