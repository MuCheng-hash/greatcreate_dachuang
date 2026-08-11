package com.redculture.platform.service.agent;

public class IdempotencyConflictException extends RuntimeException {

    private final String code;

    public IdempotencyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
