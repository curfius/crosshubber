package com.crosshubber.talenthub.connectors;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.talenthub.settings.ModuleSettingsService;

/**
 * Runtime connector registry (P4.1): beans declare (category, kind); module settings pick the
 * active kind per category, defaulting to the emulated/dev implementation per category. Selection
 * is runtime — a missing bean for a configured kind degrades to the category default instead of
 * failing boot or the call. Resolution order: configured kind → default kind → any single bean in
 * the category.
 */
@Service
public class ConnectorRegistry {

  private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

  /** Per-category default kinds (dev insecure-by-design: emulated first, docsource legacy fake). */
  static final Map<ConnectorCategory, String> DEFAULT_KINDS =
      Map.of(
          ConnectorCategory.CRM, "emulated",
          ConnectorCategory.TALENT_SOURCE, "emulated",
          ConnectorCategory.DOCUMENT_STORE, "fake",
          ConnectorCategory.MESSAGING, "emulated",
          ConnectorCategory.HRIS, "emulated");

  private final Map<ConnectorCategory, Map<String, ModuleConnector>> beans;
  private final ModuleSettingsService settings;

  public ConnectorRegistry(List<ModuleConnector> connectors, ModuleSettingsService settings) {
    this.beans =
        connectors.stream()
            .collect(
                Collectors.groupingBy(
                    ModuleConnector::category,
                    () -> new EnumMap<>(ConnectorCategory.class),
                    Collectors.toMap(ModuleConnector::kind, c -> c, (a, b) -> a)));
    this.settings = settings;
  }

  /** Known kinds per category (settings UI + validation). */
  public List<String> kindsFor(ConnectorCategory category) {
    return beans.getOrDefault(category, Map.of()).keySet().stream().sorted().toList();
  }

  /** The active connector for a category, or null when the category has no beans at all. */
  public ModuleConnector resolve(ConnectorCategory category) {
    Map<String, ModuleConnector> byKind = beans.getOrDefault(category, Map.of());
    if (byKind.isEmpty()) {
      return null;
    }
    String configured = configuredKind(category);
    String defaultKind = DEFAULT_KINDS.get(category);
    ModuleConnector connector = configured != null ? byKind.get(configured) : null;
    if (connector == null && defaultKind != null) {
      connector = byKind.get(defaultKind);
    }
    if (connector == null && byKind.size() == 1) {
      connector = byKind.values().iterator().next();
    }
    if (connector != null && configured != null && !configured.equals(connector.kind())) {
      log.warn(
          "[connectors] {} has no bean for kind \"{}\" — using \"{}\"",
          category.key(),
          configured,
          connector.kind());
    }
    return connector;
  }

  /**
   * Registry self-test: emulated implementations answer with a deterministic pong; real adapters
   * extend this later (the MVP contract never surprises with hidden I/O).
   */
  public Map<String, Object> test(ConnectorCategory category) {
    ModuleConnector connector = resolve(category);
    if (connector == null) {
      throw new IllegalArgumentException("no connector available for " + category.key());
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("category", category.key());
    result.put("kind", connector.kind());
    result.put("ok", connector.kind().startsWith("emulated") || connector.kind().equals("fake"));
    result.put(
        "detail",
        connector.kind().startsWith("emulated") || connector.kind().equals("fake")
            ? "deterministic pong (emulated)"
            : "connector has no test implementation");
    return result;
  }

  private String configuredKind(ConnectorCategory category) {
    Map<String, Object> config = settings.connectors().categories().get(category.key());
    if (config == null) {
      // Legacy migration: the docsource env property seeds the documentStore default.
      if (category == ConnectorCategory.DOCUMENT_STORE) {
        String legacy = settings.docsourceLegacyKind();
        return legacy == null ? DEFAULT_KINDS.get(category) : legacy;
      }
      return DEFAULT_KINDS.get(category);
    }
    Object kind = config.get("kind");
    return kind == null ? DEFAULT_KINDS.get(category) : kind.toString();
  }
}
