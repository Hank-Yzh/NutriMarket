package com.nutrimarket.nutrimarket.controller.advice;

import com.nutrimarket.nutrimarket.exception.AuthException;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AuthExceptionHandler {
    public record AuthError(String code, String message, Instant timestamp) {}
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthError> handle(AuthException e) {
        return ResponseEntity.status(e.status()).body(new AuthError(e.code(),e.getMessage(),Instant.now()));
    }
}
