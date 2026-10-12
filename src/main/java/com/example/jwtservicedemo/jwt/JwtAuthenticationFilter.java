package com.example.jwtservicedemo.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that intercepts incoming HTTP requests, extracts the JWT Bearer token
 * from the 'Authorization' header, validates the signature and expiration,
 * and sets the authenticated principal into Spring's SecurityContextHolder.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final RsaJwtService rsaJwtService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(RsaJwtService rsaJwtService, UserDetailsService userDetailsService) {
        this(rsaJwtService, userDetailsService, new TokenBlacklistService(rsaJwtService));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JwtAuthenticationFilter(RsaJwtService rsaJwtService,
                                   UserDetailsService userDetailsService,
                                   TokenBlacklistService tokenBlacklistService) {
        this.rsaJwtService = rsaJwtService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService != null ? tokenBlacklistService : new TokenBlacklistService(rsaJwtService);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Fast-path: Skip JWT processing if header is missing or does not start with "Bearer "
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        // Instant Revocation: If token has been blacklisted upon logout, do not authenticate
        if (tokenBlacklistService.isBlacklisted(jwt)) {
            logger.warn("Rejected blacklisted JWT access token: " + jwt);
            filterChain.doFilter(request, response);
            return;
        }

        // Restrict MFA Temporary Tokens: These tokens are only valid for the /api/auth/mfa/verify-login endpoint
        if (rsaJwtService.isMfaTempToken(jwt)) {
            logger.debug("MFA temporary token presented for general API request. Skipping authentication.");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String username = rsaJwtService.extractUsername(jwt);

            // Authenticate if a valid username was extracted and security context is not yet populated
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

                if (rsaJwtService.isTokenValid(jwt, userDetails.getUsername())) {
                    java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> authorities = userDetails.getAuthorities();
                    String tokenRole = rsaJwtService.extractRole(jwt);
                    if (tokenRole != null && !tokenRole.isBlank()) {
                        if (!tokenRole.toUpperCase().startsWith("ROLE_")) {
                            tokenRole = "ROLE_" + tokenRole.toUpperCase();
                        }
                        authorities = java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority(tokenRole));
                    }

                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            authorities
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception ex) {
            // Token is malformed, expired, or has invalid signature.
            // Log warning; SecurityContext remains empty so Spring Security will reject if endpoint is protected.
            logger.warn("Cannot set user authentication from JWT: " + ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
