package com.example.jwtservicedemo.exception;

/**
 * Exception thrown when an authentication attempt is made against an account
 * that has been temporarily locked due to excessive consecutive failed login attempts.
 */
public class AccountLockedException extends RuntimeException {

    private final long remainingMinutes;

    public AccountLockedException(String message, long remainingMinutes) {
        super(message);
        this.remainingMinutes = remainingMinutes;
    }

    public long getRemainingMinutes() {
        return remainingMinutes;
    }
}
