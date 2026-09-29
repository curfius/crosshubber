package com.crosshubber.staffing.security;

import java.util.List;

/** Identity carried by a portal-minted agent-call token (see docs/agent-protocol.md). */
public record AgentPrincipal(String sub, String name, List<String> roles) {

  public AgentPrincipal {
    if (roles == null) {
      roles = List.of();
    }
  }
}
