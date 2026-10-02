package com.crosshubber.solutions.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.solutions.config.SolutionsProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Module-owned settings storage (DB-backed, JSONB groups). Provider credentials are encrypted at
 * rest; env config stays the fallback so compose-only deployments keep working. Consumers: the
 * module settings UI (REST), {@code LlmClient} (credentials/system prompt), {@code
 * AgentToolsController} (tool toggles).
 */
@Service
public class ModuleSettingsService {

  public static final String GROUP_AGENT = "agent";
  public static final String GROUP_TOOLS = "tools";
  public static final String GROUP_RAG = "rag";

  public static final String DEFAULT_SYSTEM_PROMPT =
      "You are the Solutions module agent (Crosshubber portal). You answer questions about "
          + "project delivery status using ONLY the live project data provided. Be concise.";

  public static final int DEFAULT_MAX_DOCS_PER_QUERY = 3;
  public static final int DEFAULT_TRUNCATION_BUDGET = 2000;

  /** Agent group as stored (API key encrypted at rest). */
  public record AgentSettings(
      Boolean enabled,
      String systemPrompt,
      String providerBaseUrl,
      String providerApiKeyEnc,
      String providerModel) {}

  public record ToolSettings(Map<String, Boolean> enabled) {}

  public record RagSettings(Integer maxDocsPerQuery, Integer truncationBudget) {}

  /** Agent group as served to the UI (API key masked by a presence flag). */
  public record AgentSettingsView(
      boolean enabled,
      String systemPrompt,
      String providerBaseUrl,
      boolean hasApiKey,
      String providerModel) {}

  public record SettingsView(
      AgentSettingsView agent, Map<String, Boolean> tools, RagSettings rag) {}

  public record EffectiveLlm(String baseUrl, String apiKey, String model) {
    public boolean isConfigured() {
      return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
    }
  }

  private final ModuleSettingRepository repo;
  private final CryptoService crypto;
  private final SolutionsProperties props;
  private final ObjectMapper objectMapper;

  public ModuleSettingsService(
      ModuleSettingRepository repo,
      CryptoService crypto,
      SolutionsProperties props,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.crypto = crypto;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  // ── Reads ────────────────────────────────────────────────────────────

  public AgentSettings agent() {
    return readGroup(
        GROUP_AGENT, AgentSettings.class, new AgentSettings(null, null, null, null, null));
  }

  public ToolSettings tools() {
    return readGroup(GROUP_TOOLS, ToolSettings.class, new ToolSettings(Map.of()));
  }

  public RagSettings rag() {
    return readGroup(
        GROUP_RAG,
        RagSettings.class,
        new RagSettings(DEFAULT_MAX_DOCS_PER_QUERY, DEFAULT_TRUNCATION_BUDGET));
  }

  public boolean agentEnabled() {
    Boolean enabled = agent().enabled();
    return enabled == null || enabled;
  }

  public String systemPrompt() {
    String prompt = agent().systemPrompt();
    return prompt == null || prompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : prompt;
  }

  public boolean isToolEnabled(String toolName) {
    Boolean enabled = tools().enabled().get(toolName);
    return enabled == null || enabled;
  }

  /** Credentials resolution: stored settings first, env fallback. */
  public EffectiveLlm effectiveLlm() {
    AgentSettings a = agent();
    SolutionsProperties.Llm env = props.getLlm();
    String baseUrl = firstNonBlank(a.providerBaseUrl(), env.getBaseUrl());
    String apiKey = firstNonBlank(decrypted(a.providerApiKeyEnc()), env.getApiKey());
    String model = firstNonBlank(a.providerModel(), env.getModel());
    return new EffectiveLlm(baseUrl, apiKey, model);
  }

  public SettingsView view() {
    AgentSettings a = agent();
    AgentSettingsView agentView =
        new AgentSettingsView(
            agentEnabled(),
            a.systemPrompt() == null ? "" : a.systemPrompt(),
            a.providerBaseUrl() == null ? "" : a.providerBaseUrl(),
            notBlank(a.providerApiKeyEnc()),
            a.providerModel() == null ? "" : a.providerModel());
    Map<String, Boolean> tools = new LinkedHashMap<>();
    for (String name :
        List.of(
            "list_projects",
            "get_project",
            "get_stage_history",
            "update_project_stage",
            "search_project_docs")) {
      tools.put(name, isToolEnabled(name));
    }
    return new SettingsView(agentView, tools, rag());
  }

  // ── Writes ───────────────────────────────────────────────────────────

  /** Update request: omitted/null fields keep their stored value; blank apiKey clears it. */
  public record SettingsUpdateRequest(
      AgentUpdate agent, Map<String, Boolean> tools, RagUpdate rag) {}

  public record AgentUpdate(
      Boolean enabled,
      String systemPrompt,
      String providerBaseUrl,
      String providerApiKey,
      String providerModel) {}

  public record RagUpdate(Integer maxDocsPerQuery, Integer truncationBudget) {}

  /** Validates the update; returns an error message or null. */
  public String validate(SettingsUpdateRequest request) {
    if (request == null) {
      return "body is required";
    }
    if (request.agent() != null) {
      String prompt = request.agent().systemPrompt();
      if (prompt != null && prompt.length() > 4000) {
        return "agent.systemPrompt: too long (max 4000 chars)";
      }
      String model = request.agent().providerModel();
      if (model != null && model.length() > 120) {
        return "agent.providerModel: too long (max 120 chars)";
      }
    }
    if (request.tools() != null) {
      for (Map.Entry<String, Boolean> entry : request.tools().entrySet()) {
        if (!toolNames().contains(entry.getKey())) {
          return "tools: unknown tool \"" + entry.getKey() + "\"";
        }
        if (entry.getValue() == null) {
          return "tools." + entry.getKey() + ": boolean required";
        }
      }
    }
    if (request.rag() != null) {
      Integer docs = request.rag().maxDocsPerQuery();
      if (docs != null && (docs < 1 || docs > 20)) {
        return "rag.maxDocsPerQuery: must be 1-20";
      }
      Integer budget = request.rag().truncationBudget();
      if (budget != null && (budget < 200 || budget > 20000)) {
        return "rag.truncationBudget: must be 200-20000";
      }
    }
    return null;
  }

  @Transactional
  public SettingsView update(SettingsUpdateRequest request) {
    if (request.agent() != null) {
      AgentUpdate in = request.agent();
      AgentSettings current = agent();
      String apiKeyEnc = current.providerApiKeyEnc();
      if (in.providerApiKey() != null) {
        apiKeyEnc = in.providerApiKey().isBlank() ? null : crypto.encrypt(in.providerApiKey());
      }
      AgentSettings next =
          new AgentSettings(
              in.enabled() != null ? in.enabled() : current.enabled(),
              in.systemPrompt() != null ? in.systemPrompt() : current.systemPrompt(),
              in.providerBaseUrl() != null ? in.providerBaseUrl() : current.providerBaseUrl(),
              apiKeyEnc,
              in.providerModel() != null ? in.providerModel() : current.providerModel());
      writeGroup(GROUP_AGENT, next);
    }
    if (request.tools() != null) {
      Map<String, Boolean> merged = new LinkedHashMap<>(tools().enabled());
      merged.putAll(request.tools());
      writeGroup(GROUP_TOOLS, new ToolSettings(merged));
    }
    if (request.rag() != null) {
      RagUpdate in = request.rag();
      RagSettings current = rag();
      writeGroup(
          GROUP_RAG,
          new RagSettings(
              in.maxDocsPerQuery() != null ? in.maxDocsPerQuery() : current.maxDocsPerQuery(),
              in.truncationBudget() != null ? in.truncationBudget() : current.truncationBudget()));
    }
    return view();
  }

  // ── Internals ────────────────────────────────────────────────────────

  private <T> T readGroup(String key, Class<T> type, T fallback) {
    Optional<ModuleSettingEntity> row = repo.findByKey(key);
    if (row.isEmpty()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(row.get().getValue(), type);
    } catch (Exception e) {
      return fallback;
    }
  }

  @Transactional
  protected <T> void writeGroup(String key, T value) {
    ModuleSettingEntity entity =
        repo.findByKey(key)
            .orElseGet(
                () -> {
                  ModuleSettingEntity fresh = new ModuleSettingEntity();
                  fresh.setKey(key);
                  return fresh;
                });
    entity.setValue(objectMapper.writeValueAsString(value));
    entity.setUpdatedAt(Instant.now());
    repo.save(entity);
  }

  private String decrypted(String encrypted) {
    if (encrypted == null || encrypted.isBlank()) {
      return null;
    }
    try {
      return crypto.decrypt(encrypted);
    } catch (Exception e) {
      return null;
    }
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** Declared tool names — single source of truth is the tools controller. */
  public static List<String> toolNames() {
    return com.crosshubber.solutions.agent.AgentToolsController.toolNames();
  }
}
