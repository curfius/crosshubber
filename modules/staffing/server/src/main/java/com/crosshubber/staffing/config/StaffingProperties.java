package com.crosshubber.staffing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Module configuration (env-driven; dev defaults insecure-by-design). */
@ConfigurationProperties(prefix = "staffing")
public class StaffingProperties {

  /** Shared secret for validating portal-minted agent-call tokens (X-Portal-Agent). */
  private String agentSharedSecret = "dev-insecure-shared-secret";

  private final Docsource docsource = new Docsource();

  public String getAgentSharedSecret() {
    return agentSharedSecret;
  }

  public void setAgentSharedSecret(String agentSharedSecret) {
    this.agentSharedSecret = agentSharedSecret;
  }

  public Docsource getDocsource() {
    return docsource;
  }

  /** Document source selection: {@code fake} (in-repo stub) or {@code onedrive} (future). */
  public static class Docsource {

    private String kind = "fake";

    public String getKind() {
      return kind;
    }

    public void setKind(String kind) {
      this.kind = kind;
    }
  }
}
