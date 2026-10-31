package com.nutrimarket.nutrimarket.controller.advice;

import com.nutrimarket.nutrimarket.exception.AgentException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorResponse(String code,String message,Instant timestamp) {}
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("RECORD_CONFLICT","This record is being processed or already exists. Check the record before retrying.",Instant.now()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> database(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("DATABASE_ERROR","Database operation failed. Please try again later.",Instant.now()));
    }
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ErrorResponse> agent(AgentException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(),e.getMessage(),Instant.now()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,
            HttpMessageNotReadableException.class,MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST","Check the request fields, username format, password length and numeric ranges.",Instant.now()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse("IMAGE_TOO_LARGE","The image must not exceed 10MB.",Instant.now()));
    }
}
