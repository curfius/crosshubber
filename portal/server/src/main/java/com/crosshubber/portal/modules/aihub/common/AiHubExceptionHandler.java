package com.crosshubber.portal.modules.aihub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice(basePackages = "com.crosshubber.portal.modules.aihub")
public class AiHubExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(AiHubExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(new ErrorResponse(ex.getReason(), ex.getStatusCode().value()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletResponse response) {
    if (response.isCommitted()) {
      log.warn("[ai-hub] exception after response committed, skipping", ex);
      return null;
    }
    log.error("[ai-hub] unhandled error", ex);
    return ResponseEntity.internalServerError()
        .body(new ErrorResponse("internal server error", 500));
  }
}
