package com.crosshubber.staffing.security;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the current {@link AgentPrincipal} from the security context (set by {@link
 * AgentAuthenticationFilter} for agent-call-token requests). Used instead of
 * {@code @AuthenticationPrincipal} so controllers are testable with plain MockMvc.
 */
public final class AgentPrincipals {

  private AgentPrincipals() {}

  /** The current principal, or null when the request is anonymous. */
  public static AgentPrincipal current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AgentPrincipal principal) {
      return principal;
    }
    return null;
  }

  /** Current principal's display name, or "unknown" for anonymous requests. */
  public static String currentName() {
    AgentPrincipal principal = current();
    return principal == null || principal.name() == null || principal.name().isBlank()
        ? "unknown"
        : principal.name();
  }
}
