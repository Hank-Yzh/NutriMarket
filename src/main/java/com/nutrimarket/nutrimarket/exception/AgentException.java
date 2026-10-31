package com.nutrimarket.nutrimarket.exception;

import org.springframework.http.HttpStatus;

public class AgentException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public AgentException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static AgentException conflict(String message) {
        return new AgentException(HttpStatus.CONFLICT, "INVALID_STATE", message);
    }
    public static AgentException provider(String code, String message) {
        return new AgentException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
