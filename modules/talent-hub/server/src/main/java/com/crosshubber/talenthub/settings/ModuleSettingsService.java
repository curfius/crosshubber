package com.crosshubber.talenthub.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.talenthub.agent.AgentToolsController;
import com.crosshubber.talenthub.config.TalentHubProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Module-owned settings storage (DB-backed, JSONB groups). LLM credentials are encrypted at rest;
 * env config stays the fallback so compose-only deployments keep working. Consumers: the module
 * settings UI (REST), {@code LlmClient} (credentials), {@code AgentToolsController} (tool toggles).
 * The single source of truth for tool names is the tools controller.
 */
@Service
public class ModuleSettingsService {

  public static final String GROUP_LLM = "llm";
  public static final String GROUP_MATCHING = "matching";
  public static final String GROUP_TOOLS = "tools";
  public static final String GROUP_CONNECTORS = "connectors";

  /** LLM group as stored (API key encrypted at rest). */
  public record LlmSettings(String baseUrl, String apiKeyEnc, String model) {}

  /** LLM group as served to the UI (API key masked by a presence flag). */
  public record LlmView(String baseUrl, boolean hasApiKey, String model) {}

  public record ToolSettings(Map<String, Boolean> enabled) {}

  /** Matching group as stored (jev API key encrypted at rest). */
  public record MatchingSettings(
      String engine, String jevBaseUrl, String jevApiKeyEnc, Integer jevTimeoutMs) {}

  /** Matching group as served (jev key masked by presence flag). */
  public record MatchingView(
      String engine, String jevBaseUrl, boolean hasApiKey, Integer jevTimeoutMs) {}

  public record SettingsView(
      LlmView llm,
      MatchingView matching,
      Map<String, Boolean> tools,
      Map<String, Map<String, Object>> connectors) {}

  /** Connectors group as stored: per category {kind, config}; secret config values encrypted. */
  public record ConnectorSettings(Map<String, Map<String, Object>> categories) {}

  public ConnectorSettings connectors() {
    return readGroup(GROUP_CONNECTORS, ConnectorSettings.class, new ConnectorSettings(Map.of()));
  }

  /** Legacy docsource seed used by the connector registry until a kind is stored. */
  public String docsourceLegacyKind() {
    return props.getDocsource().getKind();
  }

  /** Effective config for a category with encrypted values resolved (registry/test usage). */
  public Map<String, Object> resolvedConnectorConfig(String categoryKey) {
    Map<String, Map<String, Object>> categories = connectors().categories();
    Map<String, Object> config = categories.get(categoryKey);
    if (config == null) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      if (isSecret(entry.getKey())) {
        out.put(entry.getKey(), decryptValue(entry.getValue()));
      } else {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  private Map<String, Object> maskConnectorConfig(Map<String, Object> config) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      if (entry.getKey().equals("kind")) {
        out.put(entry.getKey(), entry.getValue());
      } else if (isSecret(entry.getKey())) {
        boolean isSet = entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank();
        out.put(entry.getKey(), isSet ? "***" : null);
      } else {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  private Map<String, Object> encryptConnectorConfig(Map<String, Object> config) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      if (isSecret(entry.getKey())
          && entry.getValue() instanceof String value
          && !value.isBlank()
          && !value.startsWith("enc:")) {
        out.put(entry.getKey(), crypto.encrypt(value));
      } else {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  private Object decryptValue(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw);
    if (!value.startsWith("enc:")) {
      return value;
    }
    try {
      return crypto.decrypt(value.substring("enc:".length()));
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isSecret(String key) {
    return key != null
        && key.matches("(?i).*(" + "secret|apikey|api_key|token|password|key" + ").*");
  }

  public record EffectiveLlm(String baseUrl, String apiKey, String model) {
    public boolean isConfigured() {
      return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
    }
  }

  public record EffectiveJev(String baseUrl, String apiKey, Integer timeoutMs) {
    public boolean isConfigured() {
      return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
  }

  private final ModuleSettingRepository repo;
  private final CryptoService crypto;
  private final TalentHubProperties props;
  private final ObjectMapper objectMapper;

  public ModuleSettingsService(
      ModuleSettingRepository repo,
      CryptoService crypto,
      TalentHubProperties props,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.crypto = crypto;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  // ── Reads ────────────────────────────────────────────────────────────

  public LlmSettings llm() {
    return readGroup(GROUP_LLM, LlmSettings.class, new LlmSettings(null, null, null));
  }

  public ToolSettings tools() {
    return readGroup(GROUP_TOOLS, ToolSettings.class, new ToolSettings(Map.of()));
  }

  public boolean isToolEnabled(String toolName) {
    Boolean enabled = tools().enabled().get(toolName);
    return enabled == null || enabled;
  }

  /** Credentials resolution: stored settings first, env fallback. */
  public EffectiveLlm effectiveLlm() {
    LlmSettings s = llm();
    TalentHubProperties.Llm env = props.getLlm();
    String baseUrl = firstNonBlank(s.baseUrl(), env.getBaseUrl());
    String apiKey = firstNonBlank(decrypted(s.apiKeyEnc()), env.getApiKey());
    String model = firstNonBlank(s.model(), env.getModel());
    return new EffectiveLlm(baseUrl, apiKey, model);
  }

  public MatchingSettings matching() {
    return readGroup(
        GROUP_MATCHING, MatchingSettings.class, new MatchingSettings(null, null, null, null));
  }

  /** JEV credentials resolution: stored settings first, env fallback; timeout defaults 5000. */
  public EffectiveJev effectiveJev() {
    MatchingSettings s = matching();
    TalentHubProperties.Jev env = props.getJev();
    String baseUrl = firstNonBlank(s.jevBaseUrl(), env.getBaseUrl());
    String apiKey = firstNonBlank(decrypted(s.jevApiKeyEnc()), env.getApiKey());
    Integer timeout = s.jevTimeoutMs() != null ? s.jevTimeoutMs() : env.getTimeoutMs();
    return new EffectiveJev(baseUrl, apiKey, timeout == null ? 5000 : timeout);
  }

  public SettingsView view() {
    LlmSettings s = llm();
    LlmView llmView =
        new LlmView(
            s.baseUrl() == null ? "" : s.baseUrl(),
            notBlank(s.apiKeyEnc()),
            s.model() == null ? "" : s.model());
    MatchingSettings m = matching();
    MatchingView matchingView =
        new MatchingView(
            m.engine() == null ? "rules" : m.engine(),
            m.jevBaseUrl() == null ? "" : m.jevBaseUrl(),
            notBlank(m.jevApiKeyEnc()),
            m.jevTimeoutMs() == null ? 5000 : m.jevTimeoutMs());
    Map<String, Boolean> tools = new LinkedHashMap<>();
    for (String name : AgentToolsController.toolNames()) {
      tools.put(name, isToolEnabled(name));
    }
    Map<String, Map<String, Object>> connectorsMasked = new LinkedHashMap<>();
    connectors()
        .categories()
        .forEach((key, config) -> connectorsMasked.put(key, maskConnectorConfig(config)));
    return new SettingsView(llmView, matchingView, tools, connectorsMasked);
  }

  // ── Writes ───────────────────────────────────────────────────────────

  /** Update request: omitted/null fields keep their stored value; blank apiKey clears it. */
  public record SettingsUpdateRequest(
      LlmUpdate llm,
      MatchingUpdate matching,
      Map<String, Boolean> tools,
      Map<String, ConnectorUpsert> connectors) {}

  public record LlmUpdate(String baseUrl, String apiKey, String model) {}

  public record MatchingUpdate(
      String engine, String jevBaseUrl, String jevApiKey, Integer jevTimeoutMs) {}

  public record ConnectorUpsert(String kind, Map<String, Object> config) {}

  /** Validates the update; returns an error message or null. */
  public String validate(SettingsUpdateRequest request) {
    if (request == null) {
      return "body is required";
    }
    if (request.llm() != null) {
      if (request.llm().baseUrl() != null && request.llm().baseUrl().length() > 500) {
        return "llm.baseUrl: too long (max 500 chars)";
      }
      if (request.llm().model() != null && request.llm().model().length() > 120) {
        return "llm.model: too long (max 120 chars)";
      }
    }
    if (request.matching() != null) {
      String engine = request.matching().engine();
      if (engine != null && !List.of("rules", "jev", "llm").contains(engine)) {
        return "matching.engine: must be rules|jev|llm";
      }
      Integer timeout = request.matching().jevTimeoutMs();
      if (timeout != null && (timeout < 1000 || timeout > 60000)) {
        return "matching.jevTimeoutMs: must be 1000-60000";
      }
    }
    if (request.tools() != null) {
      for (Map.Entry<String, Boolean> entry : request.tools().entrySet()) {
        if (!AgentToolsController.toolNames().contains(entry.getKey())) {
          return "tools: unknown tool \"" + entry.getKey() + "\"";
        }
        if (entry.getValue() == null) {
          return "tools." + entry.getKey() + ": boolean required";
        }
      }
    }
    if (request.connectors() != null) {
      for (Map.Entry<String, ConnectorUpsert> entry : request.connectors().entrySet()) {
        if (com.crosshubber.talenthub.connectors.ConnectorCategory.fromKey(entry.getKey())
            == null) {
          return "connectors: unknown category \"" + entry.getKey() + "\"";
        }
        ConnectorUpsert upsert = entry.getValue();
        if (upsert.kind() != null && upsert.kind().length() > 64) {
          return "connectors." + entry.getKey() + ".kind: too long (max 64 chars)";
        }
      }
    }
    return null;
  }

  @Transactional
  public SettingsView update(SettingsUpdateRequest request) {
    if (request.llm() != null) {
      LlmUpdate in = request.llm();
      LlmSettings current = llm();
      String apiKeyEnc = current.apiKeyEnc();
      if (in.apiKey() != null) {
        apiKeyEnc = in.apiKey().isBlank() ? null : crypto.encrypt(in.apiKey());
      }
      writeGroup(
          GROUP_LLM,
          new LlmSettings(
              in.baseUrl() != null ? in.baseUrl() : current.baseUrl(),
              apiKeyEnc,
              in.model() != null ? in.model() : current.model()));
    }
    if (request.matching() != null) {
      MatchingUpdate in = request.matching();
      MatchingSettings current = matching();
      String jevKeyEnc = current.jevApiKeyEnc();
      if (in.jevApiKey() != null) {
        jevKeyEnc = in.jevApiKey().isBlank() ? null : crypto.encrypt(in.jevApiKey());
      }
      writeGroup(
          GROUP_MATCHING,
          new MatchingSettings(
              in.engine() != null ? in.engine() : current.engine(),
              in.jevBaseUrl() != null ? in.jevBaseUrl() : current.jevBaseUrl(),
              jevKeyEnc,
              in.jevTimeoutMs() != null ? in.jevTimeoutMs() : current.jevTimeoutMs()));
    }
    if (request.tools() != null) {
      Map<String, Boolean> merged = new LinkedHashMap<>(tools().enabled());
      merged.putAll(request.tools());
      writeGroup(GROUP_TOOLS, new ToolSettings(merged));
    }
    if (request.connectors() != null) {
      Map<String, Map<String, Object>> merged = new LinkedHashMap<>(connectors().categories());
      for (Map.Entry<String, ConnectorUpsert> entry : request.connectors().entrySet()) {
        if (com.crosshubber.talenthub.connectors.ConnectorCategory.fromKey(entry.getKey())
            == null) {
          throw new IllegalArgumentException(
              "connectors: unknown category \"" + entry.getKey() + "\"");
        }
        ConnectorUpsert in = entry.getValue();
        Map<String, Object> prior = merged.get(entry.getKey());
        Map<String, Object> config = new LinkedHashMap<>(prior == null ? Map.of() : prior);
        if (in.kind() != null) {
          config.put("kind", in.kind());
        }
        if (in.config() != null) {
          config.putAll(in.config());
        }
        merged.put(entry.getKey(), encryptConnectorConfig(config));
      }
      writeGroup(GROUP_CONNECTORS, new ConnectorSettings(merged));
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

  private <T> void writeGroup(String key, T value) {
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
}
