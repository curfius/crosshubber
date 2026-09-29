package com.crosshubber.solutions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Module configuration (env-driven; dev defaults insecure-by-design). */
@ConfigurationProperties(prefix = "solutions")
public class SolutionsProperties {

  /** Shared secret for validating portal-minted agent-call tokens (X-Portal-Agent). */
  private String agentSharedSecret = "dev-insecure-shared-secret";

  private final Llm llm = new Llm();

  private final Docsource docsource = new Docsource();

  public String getAgentSharedSecret() {
    return agentSharedSecret;
  }

  public void setAgentSharedSecret(String agentSharedSecret) {
    this.agentSharedSecret = agentSharedSecret;
  }

  public Llm getLlm() {
    return llm;
  }

  public Docsource getDocsource() {
    return docsource;
  }

  /** Module-owned LLM credentials (OpenAI-compatible). Blank = agent tasks fail closed. */
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

    public boolean isConfigured() {
      return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
    }
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
