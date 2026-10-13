package com.crosshubber.clienthub.security;

import java.util.List;

/** Identity carried by a portal-minted agent-call token. */
public record AgentPrincipal(String sub, String name, List<String> roles) {

  public AgentPrincipal {
    if (roles == null) {
      roles = List.of();
    }
  }
}
