package com.example.jwtservicedemo.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe token-bucket rate limiting service powered by Bucket4j and Caffeine.
 * Provides differentiated rate-limit policies:
 * 1. AUTH: Stricter limits on brute-force attack vectors (/login, /register).
 * 2. GENERAL: Relaxed limits for normal resource endpoints.
 */
@Service
public class RateLimitingService {

    public enum PolicyType {
        AUTH,
        GENERAL
    }

    private final long authCapacity;
    private final long authRefillTokens;
    private final Duration authDuration;

    private final long generalCapacity;
    private final long generalRefillTokens;
    private final Duration generalDuration;

    // Bounded in-memory cache to prevent memory leaks from arbitrary IP spoofing
    private final Cache<String, Bucket> bucketCache;

    public RateLimitingService(
            @Value("${ratelimit.auth.capacity:5}") long authCapacity,
            @Value("${ratelimit.auth.refill-tokens:5}") long authRefillTokens,
            @Value("${ratelimit.auth.duration-seconds:60}") long authDurationSeconds,
            @Value("${ratelimit.general.capacity:30}") long generalCapacity,
            @Value("${ratelimit.general.refill-tokens:30}") long generalRefillTokens,
            @Value("${ratelimit.general.duration-seconds:60}") long generalDurationSeconds) {

        this.authCapacity = authCapacity;
        this.authRefillTokens = authRefillTokens;
        this.authDuration = Duration.ofSeconds(authDurationSeconds);

        this.generalCapacity = generalCapacity;
        this.generalRefillTokens = generalRefillTokens;
        this.generalDuration = Duration.ofSeconds(generalDurationSeconds);

        this.bucketCache = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .maximumSize(50_000)
                .build();
    }

    /**
     * Attempts to consume 1 token for the specified client and target URI.
     *
     * @param clientIp   Client IP address
     * @param requestUri Incoming HTTP request URI
     * @return ConsumptionProbe with consumption result, remaining tokens, and refill wait time
     */
    public ConsumptionProbe tryConsume(String clientIp, String requestUri) {

        PolicyType policyType = resolvePolicy(requestUri);
        String cacheKey = policyType.name() + ":" + (clientIp != null ? clientIp : "UNKNOWN");
        Bucket bucket = bucketCache.get(cacheKey, k -> createBucket(policyType));
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    /**
     * Resolves the appropriate policy based on the requested endpoint URI.
     */
    public PolicyType resolvePolicy(String requestUri) {

        if (requestUri != null && (requestUri.endsWith("/login") || requestUri.endsWith("/register") || requestUri.endsWith("/refresh"))) {
            return PolicyType.AUTH;
        }
        return PolicyType.GENERAL;
    }

    /**
     * Creates a new token bucket with greedy refill based on the policy type.
     */
    private Bucket createBucket(PolicyType policyType) {

        Bandwidth bandwidth = policyType == PolicyType.AUTH
                ? Bandwidth.builder().capacity(authCapacity).refillGreedy(authRefillTokens, authDuration).build()
                : Bandwidth.builder().capacity(generalCapacity).refillGreedy(generalRefillTokens, generalDuration).build();

        return Bucket.builder()
                .addLimit(bandwidth)
                .build();
    }

    /**
     * Clears all cached buckets (useful for test isolation).
     */
    public void reset() {


        bucketCache.invalidateAll();
    }
}
