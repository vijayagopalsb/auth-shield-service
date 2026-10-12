package com.example.jwtservicedemo.controller;

import com.example.jwtservicedemo.jwt.RsaJwtService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller exposing the JSON Web Key Set (JWKS) per RFC 7517.
 * Downstream microservices, API Gateways (Spring Cloud Gateway, Kong, Envoy),
 * and OAuth2 Resource Servers query these endpoints to dynamically fetch and cache
 * the RSA Public Key for local RS256 signature verification.
 */
@RestController
public class JwksController {

    private final RsaJwtService rsaJwtService;

    public JwksController(RsaJwtService rsaJwtService) {
        this.rsaJwtService = rsaJwtService;
    }

    /**
     * Standard OpenID Connect / OAuth 2.0 JWKS Discovery Endpoint (RFC 7517).
     * Used by spring.security.oauth2.resourceserver.jwt.jwk-set-uri
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getJwksStandard() {
        return ResponseEntity.ok(rsaJwtService.getJwks());
    }

    /**
     * API-prefixed alias for JWKS discovery.
     */
    @GetMapping(value = "/api/auth/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getJwksApi() {
        return ResponseEntity.ok(rsaJwtService.getJwks());
    }

    /**
     * Exposes the raw Public Key in PEM format.
     */
    @GetMapping(value = "/api/auth/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPublicKeyPem() {
        return ResponseEntity.ok(rsaJwtService.getPublicKeyAsPem());
    }
}
