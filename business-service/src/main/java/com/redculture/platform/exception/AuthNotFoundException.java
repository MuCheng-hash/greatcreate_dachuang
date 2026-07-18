package com.redculture.platform.exception;

public class AuthNotFoundException extends RuntimeException {

    public AuthNotFoundException(String message) {
        super(message);
    }
}
