package com.smartticket.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-100)
public class ApiFailureHandler {
    @ExceptionHandler(ApiFailure.class)
    public ResponseEntity<?> failure(ApiFailure e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
