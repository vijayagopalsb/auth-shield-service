package com.example.jwtservicedemo;

import com.example.jwtservicedemo.jwt.RsaJwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
public class JwksEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RsaJwtService rsaJwtService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Public Endpoint: GET /.well-known/jwks.json returns RFC 7517 compliant JWK Set without auth")
    void shouldReturnRfc7517CompliantJwks() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty", is("RSA")))
                .andExpect(jsonPath("$.keys[0].use", is("sig")))
                .andExpect(jsonPath("$.keys[0].alg", is("RS256")))
                .andExpect(jsonPath("$.keys[0].kid", is(RsaJwtService.KEY_ID)))
                .andExpect(jsonPath("$.keys[0].n", notNullValue()))
                .andExpect(jsonPath("$.keys[0].e", notNullValue()))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode jwk = root.get("keys").get(0);

        String nStr = jwk.get("n").asText();
        String eStr = jwk.get("e").asText();

        // Verify mathematical validity by reconstructing RSAPublicKey from n and e
        byte[] nBytes = Base64.getUrlDecoder().decode(nStr);
        byte[] eBytes = Base64.getUrlDecoder().decode(eStr);
        BigInteger modulus = new BigInteger(1, nBytes);
        BigInteger exponent = new BigInteger(1, eBytes);

        RSAPublicKeySpec keySpec = new RSAPublicKeySpec(modulus, exponent);
        PublicKey reconstructedPublicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);

        assertNotNull(reconstructedPublicKey);

        // Generate token and verify that the reconstructed key validates its signature
        String token = rsaJwtService.generateToken("jwks_client_service");
        Claims claims = Jwts.parser()
                .verifyWith(reconstructedPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("jwks_client_service", claims.getSubject());
    }

    @Test
    @DisplayName("Public Endpoint: GET /api/auth/jwks.json returns matching JWKS")
    void shouldReturnJwksOnApiPrefixedPath() throws Exception {
        mockMvc.perform(get("/api/auth/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys[0].kid", is(RsaJwtService.KEY_ID)));
    }

    @Test
    @DisplayName("Public Endpoint: GET /api/auth/public-key returns standard PEM format")
    void shouldReturnPublicKeyInPemFormat() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/public-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn();

        String pem = result.getResponse().getContentAsString();
        assertTrue(pem.contains("-----BEGIN PUBLIC KEY-----"));
        assertTrue(pem.contains("-----END PUBLIC KEY-----"));
    }

    @Test
    @DisplayName("Generated JWT header includes kid matching the JWKS kid")
    void tokenHeaderShouldIncludeKeyId() {
        String token = rsaJwtService.generateToken("user_with_kid");

        // Split JWT into header, payload, signature
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(headerJson.contains("\"kid\":\"" + RsaJwtService.KEY_ID + "\""));
        assertTrue(headerJson.contains("\"alg\":\"RS256\""));
    }
}
