package com.crosshubber.clienthub.security;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-side role guards for Client Hub. Read endpoints accept {@code
 * portal-client-hub-view} and above; writes require {@code portal-client-hub-edit}.
 */
public final class ModuleRoles {

  public static final String EDIT = "portal-client-hub-edit";
  public static final String VIEW = "portal-client-hub-view";

  private ModuleRoles() {}

  /** Read endpoints: any client-hub role (view or edit). */
  public static void requireReader() {
    requireRole(List.of(VIEW, EDIT), "client-hub view role required");
  }

  /** Write endpoints: edit role only. */
  public static void requireEditor() {
    requireRole(List.of(EDIT), "client-hub edit role required");
  }

  /** Role gate for ad-hoc role sets. */
  public static void requireRole(List<String> accepted, String message) {
    AgentPrincipal user = AgentPrincipals.current();
    if (user == null || user.roles().stream().noneMatch(accepted::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
