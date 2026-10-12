package com.example.jwtservicedemo.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitingServiceTest {

    private RateLimitingService rateLimitingService;

    @BeforeEach
    void setUp() {
        // Auth limit: 5 requests / min, General limit: 20 requests / min
        rateLimitingService = new RateLimitingService(5, 5, 60, 20, 20, 60);
    }

    @Test
    @DisplayName("Should correctly resolve policy types based on request URI")
    void shouldResolvePolicyTypes() {
        assertEquals(RateLimitingService.PolicyType.AUTH, rateLimitingService.resolvePolicy("/api/auth/login"));
        assertEquals(RateLimitingService.PolicyType.AUTH, rateLimitingService.resolvePolicy("/api/auth/register"));
        assertEquals(RateLimitingService.PolicyType.GENERAL, rateLimitingService.resolvePolicy("/api/auth/profile"));
        assertEquals(RateLimitingService.PolicyType.GENERAL, rateLimitingService.resolvePolicy("/api/auth/users"));
        assertEquals(RateLimitingService.PolicyType.GENERAL, rateLimitingService.resolvePolicy("/api/auth/validate"));
    }

    @Test
    @DisplayName("Should allow up to 5 auth requests and reject the 6th with 429 semantics")
    void shouldEnforceAuthRateLimit() {
        String ip = "192.168.1.100";
        String uri = "/api/auth/login";

        // Consume 5 tokens successfully
        for (int i = 0; i < 5; i++) {
            ConsumptionProbe probe = rateLimitingService.tryConsume(ip, uri);
            assertTrue(probe.isConsumed(), "Request " + (i + 1) + " should be accepted");
            assertEquals(4 - i, probe.getRemainingTokens());
        }

        // 6th request should exceed the limit
        ConsumptionProbe exceededProbe = rateLimitingService.tryConsume(ip, uri);
        assertFalse(exceededProbe.isConsumed(), "6th request should be rejected");
        assertEquals(0, exceededProbe.getRemainingTokens());
        assertTrue(exceededProbe.getNanosToWaitForRefill() > 0, "Wait time should be positive");
    }

    @Test
    @DisplayName("Should isolate buckets between different client IPs")
    void shouldIsolateBucketsByClientIp() {
        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";
        String uri = "/api/auth/login";

        // Exhaust IP 1
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitingService.tryConsume(ip1, uri).isConsumed());
        }
        assertFalse(rateLimitingService.tryConsume(ip1, uri).isConsumed());

        // IP 2 must still have full 5 tokens
        ConsumptionProbe ip2Probe = rateLimitingService.tryConsume(ip2, uri);
        assertTrue(ip2Probe.isConsumed(), "IP 2 should not be affected by IP 1 limit");
        assertEquals(4, ip2Probe.getRemainingTokens());
    }

    @Test
    @DisplayName("Should allow more requests on general endpoints (capacity 20)")
    void shouldAllowHigherCapacityOnGeneralEndpoints() {
        String ip = "10.0.0.5";
        String uri = "/api/auth/profile";

        for (int i = 0; i < 20; i++) {
            ConsumptionProbe probe = rateLimitingService.tryConsume(ip, uri);
            assertTrue(probe.isConsumed(), "Request " + (i + 1) + " on general endpoint should succeed");
        }

        // 21st request should be rejected
        ConsumptionProbe exceeded = rateLimitingService.tryConsume(ip, uri);
        assertFalse(exceeded.isConsumed(), "21st request on general endpoint should be rejected");
    }
}
