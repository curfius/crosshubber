package com.crosshubber.portal.security;

import java.util.List;

/** Authenticated user principal (OIDC subject, display name, email, realm roles). */
public record PortalUser(String sub, String name, String email, List<String> roles) {

  public PortalUser {
    if (roles == null) {
      roles = List.of();
    }
  }
}
