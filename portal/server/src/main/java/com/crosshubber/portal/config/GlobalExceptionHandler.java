package com.crosshubber.portal.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralized error handling.
 *
 * <p>Mirrors {@code portal/src/middleware/errors.ts} — always {@code {error:"..."}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
    log.warn("[portal] {} {}", status.value(), reason);
    return ResponseEntity.status(status).body(Map.of("error", reason));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .findFirst()
            .orElse("validation failed");
    log.warn("[portal] 400 {}", msg);
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
    log.warn("[portal] 400 invalid request body");
    return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    log.error("[portal] internal error: {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "internal server error"));
  }
}
