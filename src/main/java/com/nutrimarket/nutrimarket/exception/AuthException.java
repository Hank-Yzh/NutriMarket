package com.nutrimarket.nutrimarket.exception;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public AuthException(HttpStatus status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Incorrect username or password.");
    }
}
