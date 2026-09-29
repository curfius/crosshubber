package com.crosshubber.solutions.web;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  private final JdbcTemplate jdbc;

  public HealthController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Liveness + DB probe (mirrors the portal contract shape). */
  @GetMapping("/healthz")
  public Map<String, Object> healthz() {
    boolean db;
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      db = true;
    } catch (Exception e) {
      db = false;
    }
    return Map.of("ok", db, "app", "solutions", "db", db ? "up" : "down");
  }
}
