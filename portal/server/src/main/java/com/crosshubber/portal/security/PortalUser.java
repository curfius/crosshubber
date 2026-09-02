package com.crosshubber.portal.security;

import java.util.List;

/**
 * Authenticated user principal.
 *
 * <p>Mirrors {@code PortalUser} in {@code portal/src/types/index.ts}.
 */
public record PortalUser(String sub, String name, String email, List<String> roles) {

  public PortalUser {
    if (roles == null) {
      roles = List.of();
    }
  }
}
