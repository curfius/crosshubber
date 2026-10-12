package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.crosshubber.talenthub.settings.ModuleSettingsService;

/**
 * Runtime selection of the scoring backend (D7/P3.2): settings {@code matching.engine} default,
 * per-call override allowed. Unknown engines fail fast with a clear message; every declared key
 * must resolve to a real bean or selection fails at call time (P4 registry makes this
 * runtime-safe).
 */
@Component
public class FitEngineSelector {

  private final Map<String, FitEngine> enginesByKey;
  private final ModuleSettingsService settings;

  public FitEngineSelector(List<FitEngine> engines, ModuleSettingsService settings) {
    this.enginesByKey =
        engines.stream().collect(Collectors.toUnmodifiableMap(FitEngine::key, e -> e));
    this.settings = settings;
  }

  /** Declared engine keys (for validation + settings UI). */
  public List<String> knownKeys() {
    return List.copyOf(enginesByKey.keySet());
  }

  /** select override wins; settings default applies otherwise; unknown override fails fast. */
  public FitEngine select(String override) {
    if (override != null && !override.isBlank()) {
      FitEngine engine = enginesByKey.get(override);
      if (engine == null) {
        throw new IllegalArgumentException(
            "unknown fit engine \"" + override + "\" (known: " + knownKeys() + ")");
      }
      return engine;
    }
    String engineKey = settings.matching().engine();
    FitEngine engine = enginesByKey.get(engineKey == null ? "rules" : engineKey);
    if (engine == null) {
      throw new IllegalStateException(
          "configured fit engine \""
              + engineKey
              + "\" has no implementation (known: "
              + knownKeys()
              + ")");
    }
    return engine;
  }
}
