package com.example.jwtservicedemo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller demonstrating hierarchical role-based access control.
 * Hierarchy: ROLE_ADMIN -> implies ROLE_MANAGER -> implies ROLE_USER.
 */
@RestController
@RequestMapping("/api/demo")
public class HierarchicalAccessController {

    /**
     * User area: Accessible by USER, MANAGER, and ADMIN.
     */
    @GetMapping("/user-area")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> userArea() {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to User Area! Accessible by USER, MANAGER, and ADMIN",
                "requiredRole", "ROLE_USER"
        ));
    }

    /**
     * Manager area: Accessible by MANAGER and ADMIN.
     */
    @GetMapping("/manager-area")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> managerArea() {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to Manager Area! Accessible by MANAGER and ADMIN",
                "requiredRole", "ROLE_MANAGER"
        ));
    }

    /**
     * Admin area: Accessible ONLY by ADMIN.
     */
    @GetMapping("/admin-area")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adminArea() {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to Admin Area! Accessible ONLY by ADMIN",
                "requiredRole", "ROLE_ADMIN"
        ));
    }
}
