package com.example.jwtservicedemo.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
@Getter
@Service
public class RsaJwtService {

    public static final String KEY_ID = "jwt-service-rsa-key-1";
    private static final long EXPIRATION_TIME = 1000 * 60 * 15; // 15 Minutes

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public RsaJwtService() {
        try {
            this.privateKey = loadPrivateKey();
            this.publicKey = loadPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA key pair from resources", e);
        }
    }

    // ==========================================
    // Part 1: Token Generation (Signs with PRIVATE Key)
    // ==========================================

    // Generates a token with username only
    public String generateToken(String username) {


        return generateToken(new HashMap<>(), username);
    }

    // Generates a token with username first overload
    public String generateToken(String username, Map<String, Object> extraClaims) {

        return generateToken(extraClaims, username);
    }

    // Generates a token with custom claims and signs using RSA Private Key
    public String generateToken(Map<String, Object> extraClaims, String username) {

        long currentTimeMillis = System.currentTimeMillis();

        return Jwts.builder()
                .header()
                .type("JWT")
                .keyId(KEY_ID)
                .and()
                .id(java.util.UUID.randomUUID().toString())
                .claims(extraClaims)
                .subject(username)
                .issuedAt(new Date(currentTimeMillis))
                .expiration(new Date(currentTimeMillis + EXPIRATION_TIME))
                // Sign using ASYMMETRIC Private Key with RS256 algorithm
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    // Generates a token with custom claims, subject, and custom expiration duration
    public String generateToken(Map<String, Object> extraClaims, String username, long expirationMs) {
        long currentTimeMillis = System.currentTimeMillis();

        return Jwts.builder()
                .header()
                .type("JWT")
                .keyId(KEY_ID)
                .and()
                .id(java.util.UUID.randomUUID().toString())
                .claims(extraClaims)
                .subject(username)
                .issuedAt(new Date(currentTimeMillis))
                .expiration(new Date(currentTimeMillis + expirationMs))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generates a short-lived (5-minute) RSA-signed temporary token for Step 2 of MFA login.
     * Contains custom purpose claim: 'MFA_TEMP'.
     */
    public String generateMfaTempToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("purpose", "MFA_TEMP");
        claims.put("role", role);
        return generateToken(claims, username, 5 * 60 * 1000L); // 5 minutes
    }

    /**
     * Checks if a token is a restricted MFA temporary token.
     */
    public boolean isMfaTempToken(String token) {
        try {
            String purpose = extractClaim(token, claims -> claims.get("purpose", String.class));
            return "MFA_TEMP".equals(purpose);
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================
    // Part 2: Token Validation (Verifies with PUBLIC Key)
    // ==========================================

    // Extracts the username (Subject) using the Public Key
    public String extractUsername(String token) {


        return extractClaim(token, Claims::getSubject);
    }

    // Generic method to extract any claim using the Public Key
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {

        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    // Extracts role claim from JWT payload using RSA Public Key
    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    // Parses and validates the token signature using ONLY the Public Key
    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith(publicKey) // Microservices verify with Public Key
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ==========================================
    // Part 3: Verification & Expiration Checks
    // ==========================================

    public boolean isTokenValid(String token, String username) {

        final String extractedUsername = extractUsername(token);
        return extractedUsername.equals(username) && !isTokenExpired(token);
    }

    public boolean isTokenValid(String token, org.springframework.security.core.userdetails.UserDetails userDetails) {
        final String extractedUsername = extractUsername(token);
        return extractedUsername.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public boolean isTokenExpired(String token) {


        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {


        return extractClaim(token, Claims::getExpiration);
    }

//    @Getter
//    public PublicKey getPublicKey() {
//
//
//        return this.publicKey;
//    }

    // ==========================================
    // Helper Methods: Load PEM Files
    // ==========================================

    private PrivateKey loadPrivateKey() throws Exception {
        ClassPathResource resource = new ClassPathResource("certs/private_key.pem");
        try (InputStream is = resource.getInputStream()) {
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String privateKeyPEM = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] decoded = Base64.getDecoder().decode(privateKeyPEM);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        }
    }

    private PublicKey loadPublicKey() throws Exception {
        ClassPathResource resource = new ClassPathResource("certs/public_key.pem");
        try (InputStream is = resource.getInputStream()) {
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String publicKeyPEM = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] decoded = Base64.getDecoder().decode(publicKeyPEM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        }
    }

    // ==========================================
    // Part 4: JWKS (RFC 7517) & Public Key Export
    // ==========================================

    /**
     * Converts the RSA Public Key into an RFC 7517 JSON Web Key (JWK) structure.
     * Encodes modulus (n) and exponent (e) as Base64url unsigned big-endian bytes (RFC 7518 Section 6.3).
     */
    public Map<String, Object> getJwk() {
        java.security.interfaces.RSAPublicKey rsaPublicKey = (java.security.interfaces.RSAPublicKey) this.publicKey;

        byte[] modBytes = rsaPublicKey.getModulus().toByteArray();
        if (modBytes.length > 0 && modBytes[0] == 0) {
            modBytes = java.util.Arrays.copyOfRange(modBytes, 1, modBytes.length);
        }
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(modBytes);

        byte[] expBytes = rsaPublicKey.getPublicExponent().toByteArray();
        if (expBytes.length > 0 && expBytes[0] == 0) {
            expBytes = java.util.Arrays.copyOfRange(expBytes, 1, expBytes.length);
        }
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(expBytes);

        Map<String, Object> jwk = new java.util.LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", KEY_ID);
        jwk.put("n", n);
        jwk.put("e", e);
        return jwk;
    }

    /**
     * Returns the full JSON Web Key Set (JWKS) with standard 'keys' array.
     */
    public Map<String, Object> getJwks() {
        return Map.of("keys", java.util.List.of(getJwk()));
    }

    /**
     * Exports the Public Key in standard PEM format.
     */
    public String getPublicKeyAsPem() {
        String base64 = Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        pem.append("-----END PUBLIC KEY-----");
        return pem.toString();
    }
}
