package com.example.jwtservicedemo;

import com.example.jwtservicedemo.jwt.JwtService;
import com.example.jwtservicedemo.model.User;

import java.util.HashMap;
import java.util.Map;

public class MainTest {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("🚀 JWT SERVICE DEMO - IN-MEMORY POJO USER (NO DB)");
        System.out.println("==================================================\n");

        JwtService jwtService = new JwtService();

        // 1. Create a POJO User and set values via Lombok Setter methods
        User user1 = new User();
        user1.setUsername("alex_dev");
        user1.setPassword("SecretPass@2026");
        user1.setRole("DEVELOPER");

        System.out.println("1. Created User POJO using Lombok Setters:");
        System.out.println("   Username : " + user1.getUsername());
        System.out.println("   Password : " + user1.getPassword());
        System.out.println("   Role     : " + user1.getRole());
        System.out.println("   toString : " + user1 + "\n");

        // 2. Create another POJO User and set values via Constructor
        User user2 = new User("sarah_admin", "AdminPass#999", "ADMIN");

        System.out.println("2. Created User POJO using Constructor:");
        System.out.println("   Username : " + user2.getUsername());
        System.out.println("   Password : " + user2.getPassword());
        System.out.println("   Role     : " + user2.getRole());
        System.out.println("   toString : " + user2 + "\n");

        // 3. Create a POJO User and set values via Lombok Builder pattern
        User user3 = User.builder()
                .username("david_lead")
                .password("Lead@Secure2026")
                .role("TECH_LEAD")
                .build();

        System.out.println("3. Created User POJO using Lombok Builder (@Builder):");
        System.out.println("   Username : " + user3.getUsername());
        System.out.println("   Password : " + user3.getPassword());
        System.out.println("   Role     : " + user3.getRole());
        System.out.println("   toString : " + user3 + "\n");

        // 4. Generate JWT Token using user3's POJO properties
        Map<String, Object> customClaims = new HashMap<>();
        customClaims.put("role", user3.getRole());
        customClaims.put("department", "Engineering");

        String jwtToken = jwtService.generateToken(customClaims, user3.getUsername());
        System.out.println("4. Generated JWT Token for POJO User (" + user3.getUsername() + "):");
        System.out.println(jwtToken + "\n");

        // 5. Validate and Extract Claims from JWT
        String extractedUser = jwtService.extractUsername(jwtToken);
        boolean isValid = jwtService.isTokenValid(jwtToken, user3.getUsername());

        System.out.println("5. Token Verification:");
        System.out.println("   Extracted Username : " + extractedUser);
        System.out.println("   Matches POJO User  : " + extractedUser.equals(user3.getUsername()));
        System.out.println("   Is Token Valid     : " + isValid);

        System.out.println("\n==================================================");
        System.out.println("✅ POJO User & JWT Demonstration Complete!");
        System.out.println("==================================================");
    }
}
