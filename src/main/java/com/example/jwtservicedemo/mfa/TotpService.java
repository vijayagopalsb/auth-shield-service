package com.example.jwtservicedemo.mfa;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service implementing RFC 6238 Time-based One-Time Password (TOTP) algorithm
 * and RFC 4648 Base32 encoding for Multi-Factor Authentication (MFA).
 * Produces QR codes compatible with Google Authenticator, Microsoft Authenticator, and Postman.
 */
@Service
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MOD_DIVISOR = 1_000_000;
    private static final String ISSUER = "AuthShield";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a 160-bit (20 bytes) cryptographically secure random Base32 secret string.
     * 20 bytes = exactly 32 Base32 characters.
     */
    public String generateSecretKey() {
        byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * Verifies a 6-digit TOTP code against a Base32 secret key.
     * Allows for clock drift of ±1 window (±30 seconds).
     */
    public boolean verifyTotp(String secretBase32, String codeStr) {
        if (secretBase32 == null || codeStr == null) {
            return false;
        }
        String cleanCode = codeStr.trim();
        if (!cleanCode.matches("\\d{6}")) {
            return false;
        }

        try {
            int candidateCode = Integer.parseInt(cleanCode);
            byte[] key = decodeBase32(secretBase32);
            long currentWindow = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;

            // Check previous (-1), current (0), and next (+1) window for clock skew tolerance
            for (int windowOffset = -1; windowOffset <= 1; windowOffset++) {
                if (generateTotpCode(key, currentWindow + windowOffset) == candidateCode) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Generates the current 6-digit TOTP code for a given Base32 secret.
     * Useful for automated testing and Postman integration.
     */
    public String generateCurrentTotp(String secretBase32) {
        try {
            byte[] key = decodeBase32(secretBase32);
            long currentWindow = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
            int code = generateTotpCode(key, currentWindow);
            return String.format("%06d", code);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TOTP code: " + e.getMessage(), e);
        }
    }

    /**
     * Constructs the standard RFC 6238 otpauth:// URI recognized by Google Authenticator.
     */
    public String getOtpAuthUri(String username, String secretBase32) {
        String encodedIssuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(username, StandardCharsets.UTF_8);
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                encodedIssuer, encodedAccount, secretBase32, encodedIssuer, DIGITS, TIME_STEP_SECONDS);
    }

    /**
     * Generates a Base64-encoded PNG data URL of the QR code for display in Postman Visualizer or browsers.
     */
    public String generateQrCodeDataUrl(String otpAuthUri, int width, int height) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(otpAuthUri, BarcodeFormat.QR_CODE, width, height);

            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            byte[] pngData = pngOutputStream.toByteArray();

            return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR Code image: " + e.getMessage(), e);
        }
    }

    /**
     * Pure RFC 6238 TOTP computation using HMAC-SHA1 and Dynamic Truncation.
     */
    private int generateTotpCode(byte[] key, long timeStep) throws GeneralSecurityException {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (timeStep & 0xFF);
            timeStep >>= 8;
        }

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "RAW"));
        byte[] hash = mac.doFinal(data);

        // Dynamic truncation (RFC 4226 section 5.4)
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        return binary % MOD_DIVISOR;
    }

    /**
     * Encodes byte array into RFC 4648 Base32 string.
     */
    public static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            buffer = buffer << (5 - bitsLeft);
            sb.append(BASE32_ALPHABET.charAt(buffer & 0x1F));
        }
        return sb.toString();
    }

    /**
     * Decodes RFC 4648 Base32 string into byte array.
     */
    public static byte[] decodeBase32(String base32) {
        String clean = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
