package com.crosshubber.portal.shell;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.shell.dto.HealthDto;

/** Public health endpoint — {@code ok}/{@code db} status with 200 or 503. */
@RestController
public class HealthController {

  private final ShellHealthService healthService;

  public HealthController(ShellHealthService healthService) {
    this.healthService = healthService;
  }

  @GetMapping("/healthz")
  public ResponseEntity<HealthDto> healthz() {
    boolean up = healthService.dbStatus();
    HealthDto response = new HealthDto(up, "portal", up ? "up" : "down");
    return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(response);
  }
}
