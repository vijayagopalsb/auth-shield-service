package com.example.jwtservicedemo.audit;

import com.example.jwtservicedemo.model.SecurityAuditLog;
import com.example.jwtservicedemo.repository.SecurityAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Asynchronous/Synchronous event listener that ingests SecurityAuditEvents.
 * 1. Emits structured log records optimized for SIEM pipelines (Elasticsearch, Logstash, Datadog).
 * 2. Persists immutable audit records into MySQL for long-term compliance and admin visibility.
 */
@Component
public class SecurityAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditEventListener.class);

    private final SecurityAuditLogRepository auditLogRepository;

    @Autowired
    public SecurityAuditEventListener(@Autowired(required = false) SecurityAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @EventListener
    public void onSecurityEvent(SecurityAuditEvent event) {
        // Structured SIEM logging format: [SECURITY_AUDIT] event_type=... username=... status=... details=...
        String message = String.format("[SECURITY_AUDIT] event_type='%s' username='%s' status='%s' details='%s'",
                event.getEventType(), event.getUsername(), event.getStatus(), event.getDetails());

        if ("ALERT".equalsIgnoreCase(event.getStatus())) {
            log.warn(message);
        } else if ("FAILURE".equalsIgnoreCase(event.getStatus())) {
            log.warn(message);
        } else {
            log.info(message);
        }

        // Persist to relational database for compliance and querying
        if (auditLogRepository != null) {
            SecurityAuditLog entity = SecurityAuditLog.builder()
                    .eventType(event.getEventType())
                    .username(event.getUsername())
                    .status(event.getStatus())
                    .details(event.getDetails())
                    .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                    .build();

            auditLogRepository.save(entity);
        }
    }
}
