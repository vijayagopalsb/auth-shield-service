package com.example.jwtservicedemo;

import com.example.jwtservicedemo.jwt.RsaJwtService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class RsaMainTest {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("🚀 JWT SERVICE 2: ASYMMETRIC RS256 DEMO");
        System.out.println("=================================================\n");

        RsaJwtService rsaJwtService = new RsaJwtService();

        // 1. Prepare claims
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "MICROSERVICE_ADMIN");
        claims.put("service", "BillingService");
        claims.put("cluster", "prod-east-1");

        // 2. Generate token using RSA Private Key
        String rsaToken = rsaJwtService.generateToken("admin-user-01", claims);
        System.out.println("1. Generated RS256 JWT Token:\n" + rsaToken + "\n");

        // 3. Inspect Decoded Header (to prove it is RS256)
        String[] parts = rsaToken.split("\\.");
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        System.out.println("2. Decoded Header  : " + headerJson);
        System.out.println("3. Decoded Payload : " + payloadJson + "\n");

        // 4. Validate and Extract Claims using ONLY the Public Key
        String username = rsaJwtService.extractUsername(rsaToken);
        boolean isValid = rsaJwtService.isTokenValid(rsaToken, "admin-user-01");

        System.out.println("4. Extracted Username (via Public Key) : " + username);
        System.out.println("5. Is RS256 Token Valid                : " + isValid + "\n");

        // 5. Demonstrate Tamper-Proofing
        System.out.println("6. Testing Tamper Detection:");
        String tamperedToken = rsaToken.substring(0, rsaToken.length() - 5) + "abcde";
        try {
            rsaJwtService.isTokenValid(tamperedToken, "admin-user-01");
            System.out.println("❌ ERROR: Tampered token was accepted!");
        } catch (Exception e) {
            System.out.println("✅ SUCCESS: Tampered token was rejected! (" + e.getClass().getSimpleName() + ")");
        }

        System.out.println("\n=================================================");
        System.out.println("🎉 RS256 ASYMMETRIC VERIFICATION COMPLETE");
        System.out.println("=================================================");
    }
}
