package com.crosshubber.portal.modules.aihub.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class EntityNotFoundException extends ResponseStatusException {

  public EntityNotFoundException(String message) {
    super(HttpStatus.NOT_FOUND, message);
  }
}
