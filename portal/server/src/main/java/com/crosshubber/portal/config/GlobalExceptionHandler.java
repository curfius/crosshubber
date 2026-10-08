package com.crosshubber.portal.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

/**
 * Centralized error handling.
 *
 * <p>Always answers with the {@code {"error":"..."}} envelope (README "Design notes").
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
            .reduce((a, b) -> a + "; " + b)
            .orElse("validation failed");
    log.warn("[portal] 400 {}", msg);
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
    log.warn("[portal] 400 invalid request body");
    return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
  }

  /** Method validation on non-body parameters (e.g. {@code @PathVariable} constraints). */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Map<String, String>> handleMethodValidation(
      HandlerMethodValidationException ex) {
    String msg =
        ex.getAllErrors().stream()
            .map(error -> error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("validation failed");
    log.warn("[portal] 400 {}", msg);
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  /** {@code @Validated} bean validation outside the web layer (service-level constraints). */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, String>> handleConstraintViolation(
      ConstraintViolationException ex) {
    String msg =
        ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("validation failed");
    log.warn("[portal] 400 {}", msg);
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    log.warn("[portal] 403 forbidden");
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
  }

  /** {@code @Version} mismatch — the resource changed since the client loaded it (409). */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<Map<String, String>> handleOptimisticLock(
      OptimisticLockingFailureException ex) {
    log.warn("[portal] 409 conflict: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "conflict: resource changed concurrently - reload and retry"));
  }

  /** Message-center envelope contract violations (publish path) — 422 with the first reason. */
  @ExceptionHandler(com.crosshubber.portal.common.events.EnvelopeValidationException.class)
  public ResponseEntity<Map<String, String>> handleEnvelopeValidation(
      com.crosshubber.portal.common.events.EnvelopeValidationException ex) {
    log.warn("[portal] 422 invalid envelope: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
        .body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, String>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    String msg = ex.getName() + " has an invalid value";
    log.warn("[portal] 400 {}", msg);
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    log.error("[portal] internal error: {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "internal server error"));
  }
}
