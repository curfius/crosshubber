package com.crosshubber.talenthub.security;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-side role guards (P0.5; settings {@code requireAdmin} precedent). Enforced server-side
 * on every route — the UI only hides actions, it never decides. Read endpoints accept all four
 * staffing roles ({@code staffing-user} is read-only, D6).
 */
public final class ModuleRoles {

  public static final String MANAGER = "staffing-manager";
  public static final String RECRUITER = "staffing-recruiter";
  public static final String ADMIN = "staffing-admin";
  public static final String USER = "staffing-user";

  private ModuleRoles() {}

  /** Manager-only actions: approve, close, reject, archive, capture. */
  public static void requireManager() {
    requireRole(List.of(MANAGER), "staffing-manager role required");
  }

  /** Recruiter-or-better actions: processes, matching, submissions, talent pool writes. */
  public static void requireRecruiter() {
    requireRole(
        List.of(RECRUITER, MANAGER, ADMIN),
        "staffing-recruiter, staffing-manager or staffing-admin role required");
  }

  /** Read endpoints: every staffing role (staffing-user included). */
  public static void requireReader() {
    requireRole(List.of(USER, RECRUITER, MANAGER, ADMIN), "no staffing role granted");
  }

  /** Client writes: manager or admin (P1.1). */
  public static void requireClientWriter() {
    requireRole(List.of(MANAGER, ADMIN), "staffing-manager or staffing-admin role required");
  }

  /** Settings/configuration: admin only. */
  public static void requireAdmin() {
    requireRole(List.of(ADMIN), "staffing-admin role required");
  }

  /** Role gate for ad-hoc role sets (e.g. capture drafts: manager+admin). */
  public static void requireRole(List<String> accepted, String message) {
    AgentPrincipal user = AgentPrincipals.current();
    if (user == null || user.roles().stream().noneMatch(accepted::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
