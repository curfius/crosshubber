package com.crosshubber.portal.common;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.crosshubber.portal.security.PortalUser;

/** Helpers for reading the current {@link PortalUser} from the security context. */
public final class SecurityUtils {

  private SecurityUtils() {}

  /** The authenticated principal, or null when absent/anonymous. */
  public static PortalUser principal(Authentication auth) {
    return auth != null && auth.getPrincipal() instanceof PortalUser user ? user : null;
  }

  /** The authenticated principal from the security context, or null. */
  public static PortalUser principal() {
    return principal(SecurityContextHolder.getContext().getAuthentication());
  }

  /** The current subject, or {@code "unknown"} for anonymous callers (audit-log fallback). */
  public static String currentUserSub() {
    PortalUser user = principal();
    return user != null ? user.sub() : "unknown";
  }

  /** The current roles, or an empty list for anonymous callers. */
  public static List<String> currentUserRoles() {
    PortalUser user = principal();
    return user != null ? user.roles() : List.of();
  }
}
