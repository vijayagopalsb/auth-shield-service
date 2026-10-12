package com.example.jwtservicedemo.repository;

import com.example.jwtservicedemo.model.SecurityAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for persisting and querying immutable security audit event logs.
 */
@Repository
public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {

    /**
     * Retrieves the most recent 50 audit entries across the entire cluster in reverse chronological order.
     */
    List<SecurityAuditLog> findTop50ByOrderByTimestampDesc();

    /**
     * Retrieves all security audit records for a specific username ordered by latest first.
     */
    List<SecurityAuditLog> findByUsernameOrderByTimestampDesc(String username);
}
