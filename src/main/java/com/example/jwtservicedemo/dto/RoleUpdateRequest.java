package com.example.jwtservicedemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for role promotion / update requests by administrators.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpdateRequest {

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "^(?i)(USER|MANAGER|ADMIN)$", message = "Role must be USER, MANAGER, or ADMIN")
    private String role;
}
