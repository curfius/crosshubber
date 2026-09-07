package com.crosshubber.portal.modules.aihub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.crosshubber.portal.modules.aihub.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice(basePackages = "com.crosshubber.portal.modules.aihub")
public class AiHubExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(AiHubExceptionHandler.class);

  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(ex.getReason(), 404, null));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletResponse response) {
    if (response.isCommitted()) {
      log.warn("[ai-hub] exception after response committed, skipping", ex);
      return null;
    }
    log.error("[ai-hub] unhandled error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("internal server error", 500, null));
  }
}
