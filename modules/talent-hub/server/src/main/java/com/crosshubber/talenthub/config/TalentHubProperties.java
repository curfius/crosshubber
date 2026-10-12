package com.crosshubber.talenthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Module configuration (env-driven; dev defaults insecure-by-design). */
@ConfigurationProperties(prefix = "talenthub")
public class TalentHubProperties {

  /** Shared secret for validating portal-minted agent-call tokens (X-Portal-Agent). */
  private String agentSharedSecret = "dev-insecure-shared-secret";

  /** AES key (64 hex chars) for encrypting module-stored credentials (settings). */
  private String cryptoKey = "";

  private final Portal portal = new Portal();

  private final Llm llm = new Llm();

  private final Jev jev = new Jev();

  private final Docsource docsource = new Docsource();

  private final Documents documents = new Documents();

  public String getAgentSharedSecret() {
    return agentSharedSecret;
  }

  public void setAgentSharedSecret(String agentSharedSecret) {
    this.agentSharedSecret = agentSharedSecret;
  }

  public String getCryptoKey() {
    return cryptoKey;
  }

  public void setCryptoKey(String cryptoKey) {
    this.cryptoKey = cryptoKey;
  }

  public Portal getPortal() {
    return portal;
  }

  public Llm getLlm() {
    return llm;
  }

  public Jev getJev() {
    return jev;
  }

  public Docsource getDocsource() {
    return docsource;
  }

  public Documents getDocuments() {
    return documents;
  }

  /** Local document volume (uploaded RFP/CV files live here; module-owned storage). */
  public static class Documents {

    private String dir = "/data/documents";

    public String getDir() {
      return dir;
    }

    public void setDir(String dir) {
      this.dir = dir;
    }
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

  /** Module-owned LLM credentials (OpenAI-compatible); absent = capture analysis fails soft. */
  public static class Llm {

    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }
  }

  /** External JEV decision API credentials (env fallback; settings-first at runtime). */
  public static class Jev {

    private String baseUrl = "";
    private String apiKey = "";
    private Integer timeoutMs = 5000;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public Integer getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
      this.timeoutMs = timeoutMs;
    }
  }

  /** Document source selection: {@code fake} (in-repo stub) until real adapters land. */
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
