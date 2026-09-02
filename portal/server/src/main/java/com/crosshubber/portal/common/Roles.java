package com.crosshubber.portal.common;

import java.util.List;

/**
 * Role helper — ANY-of semantics.
 *
 * <p>Mirrors {@code portal/src/modules/_shared/roles.ts} hasAnyRole.
 */
public final class Roles {

  private Roles() {}

  /** Returns true if user has any of required roles, or if required is empty (public). */
  public static boolean hasAnyRole(List<String> userRoles, List<String> required) {
    if (required == null || required.isEmpty()) {
      return true;
    }
    if (userRoles == null || userRoles.isEmpty()) {
      return false;
    }
    for (String r : required) {
      if (userRoles.contains(r)) {
        return true;
      }
    }
    return false;
  }

  /** Parses roles stored as comma-separated or JSON array string. */
  public static List<String> parse(String raw) {
    if (raw == null || raw.isBlank() || raw.equals("{}") || raw.equals("[]")) {
      return List.of();
    }
    // Handle JSON array like ["a","b"] or string like "a,b" or Postgres array {a,b}
    String trimmed = raw.trim();
    if (trimmed.startsWith("[")) {
      // Simple JSON array parse: remove brackets and quotes
      trimmed = trimmed.substring(1, trimmed.length() - 1).replace("\"", "").trim();
      if (trimmed.isEmpty()) {
        return List.of();
      }
      return List.of(trimmed.split("\\s*,\\s*"));
    }
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      trimmed = trimmed.substring(1, trimmed.length() - 1).replace("\"", "").trim();
      if (trimmed.isEmpty()) {
        return List.of();
      }
      return List.of(trimmed.split("\\s*,\\s*"));
    }
    return List.of(trimmed.split("\\s*,\\s*"));
  }
}
