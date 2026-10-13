package com.crosshubber.clienthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Module configuration (env-driven; dev defaults insecure-by-design). */
@ConfigurationProperties(prefix = "clienthub")
public class ClientHubProperties {

  private String agentSharedSecret = "dev-insecure-shared-secret";

  private final Portal portal = new Portal();

  public String getAgentSharedSecret() {
    return agentSharedSecret;
  }

  public void setAgentSharedSecret(String agentSharedSecret) {
    this.agentSharedSecret = agentSharedSecret;
  }

  public Portal getPortal() {
    return portal;
  }

  /** Portal bridge: where module events publish to (msgcenter HTTP ingest, HMAC auth). */
  public static class Portal {

    private String url = "http://portal:3000";
    private String sessionSecret = "dev-insecure-shared-secret";

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getSessionSecret() {
      return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
      this.sessionSecret = sessionSecret;
    }
  }
}
