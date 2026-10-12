package com.example.jwtservicedemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for completing Step 2 of authentication when MFA is enabled on the account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaLoginRequest {

    @NotBlank(message = "Temporary MFA token is required")
    private String tempToken;

    @NotBlank(message = "MFA code is required")
    @Pattern(regexp = "^\\d{6}$", message = "MFA code must be exactly 6 numeric digits")
    private String code;
}
