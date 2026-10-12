package com.example.jwtservicedemo.audit;

import org.springframework.context.ApplicationEvent;

/**
 * Domain application event fired when critical security and identity events occur.
 * Decouples core authentication workflows from logging, persistence, and external alerting systems.
 */
public class SecurityAuditEvent extends ApplicationEvent {

    private final String eventType;
    private final String username;
    private final String status;
    private final String details;

    public SecurityAuditEvent(Object source, String eventType, String username, String status, String details) {
        super(source);
        this.eventType = eventType;
        this.username = username;
        this.status = status;
        this.details = details;
    }

    public String getEventType() {
        return eventType;
    }

    public String getUsername() {
        return username;
    }

    public String getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return "SecurityAuditEvent{" +
                "eventType='" + eventType + '\'' +
                ", username='" + username + '\'' +
                ", status='" + status + '\'' +
                ", details='" + details + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
