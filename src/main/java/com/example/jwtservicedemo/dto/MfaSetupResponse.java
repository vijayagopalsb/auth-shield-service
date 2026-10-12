package com.example.jwtservicedemo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO returned when initiating MFA enrollment.
 * Contains the shared Base32 secret key, standard otpauth URI, and Base64 QR code image.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaSetupResponse {

    private String secret;
    private String manualEntryKey;
    private String otpAuthUri;
    private String qrCodeDataUrl;
    private String message;
}
