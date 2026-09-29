package com.crosshubber.portal.modules.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.registry.manifest.InstallService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import tools.jackson.databind.JsonNode;

/**
 * Registry of tools the model may call (AI plan B1/B2): the built-in portal reads plus the {@code
 * agentContributions.tools[]} declared by every active installed module.
 *
 * <p>Remote contributions are resolved from the installed manifests on demand and cached for 60s —
 * install/uninstall changes take effect within a minute without reconciler coupling. Invalid
 * entries cannot reach this registry: {@code ManifestValidator} rejects malformed manifests at
 * install/boot time (fail-fast).
 */
@Service
public class ToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

  private final ModulesService modulesService;
  private final InstallService installService;
  private final BuiltinToolHandlers builtinHandlers;

  /** Short-lived catalogue cache — same lifetime model as the ChatClient cache. */
  private final Cache<String, List<AgentTool>> remoteCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(1).build();

  public ToolRegistry(
      ModulesService modulesService,
      InstallService installService,
      BuiltinToolHandlers builtinHandlers) {
    this.modulesService = modulesService;
    this.installService = installService;
    this.builtinHandlers = builtinHandlers;
  }

  /** Built-in portal tools (AI plan B1 seed set — low-risk reads). */
  public List<AgentTool> builtinTools() {
    return builtinHandlers.tools();
  }

  /**
   * Tools contributed by active installed modules (manifest {@code agentContributions.tools[]}).
   */
  public List<AgentTool> remoteTools() {
    return remoteCache.get("catalogue", k -> resolveRemote());
  }

  /** Full catalogue: builtins first, then remote contributions (stable order). */
  public List<AgentTool> catalogue() {
    List<AgentTool> all = new ArrayList<>(builtinTools());
    all.addAll(remoteTools());
    return List.copyOf(all);
  }

  /** Finds a tool by model-facing name ({@code name} or {@code moduleKey_name}). */
  public AgentTool byModelName(String modelName) {
    return catalogue().stream()
        .filter(t -> t.modelName().equals(modelName))
        .findFirst()
        .orElse(null);
  }

  private List<AgentTool> resolveRemote() {
    List<AgentTool> result = new ArrayList<>();
    for (ModuleEntity module : modulesService.list(false)) {
      if (module.getBaseUrl() == null || module.getBaseUrl().isBlank()) {
        continue;
      }
      JsonNode manifest = installService.activeManifest(module.getKey());
      if (manifest == null) {
        continue;
      }
      JsonNode tools = manifest.path("agentContributions").path("tools");
      if (!tools.isArray()) {
        continue;
      }
      for (JsonNode tool : tools) {
        String name = tool.path("name").asString(null);
        if (name == null || name.isBlank()) {
          continue;
        }
        List<String> roles = new ArrayList<>();
        if (tool.path("roles").isArray()) {
          tool.path("roles").forEach(r -> roles.add(r.asString()));
        }
        result.add(
            AgentTool.remote(
                module.getKey(),
                module.getBaseUrl(),
                name,
                tool.path("description").asString(""),
                tool.has("arguments") ? tool.get("arguments") : null,
                tool.path("mutates").asBoolean(false),
                List.copyOf(roles),
                tool.hasNonNull("path") ? tool.path("path").asString() : null));
      }
      if (!result.isEmpty()) {
        log.debug("[agent] remote tools for {}: {}", module.getKey(), result.size());
      }
    }
    return List.copyOf(result);
  }
}
