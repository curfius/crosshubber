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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Registry of tools the model may call (AI plan B1/B2): the built-in portal reads plus the {@code
 * agentContributions.tools[]} and {@code agentContributions.agents[]} declared by every active
 * installed module. Sub-agent entries surface to the model as ordinary delegating tools (AI plan
 * F2/F3); dispatch folds them through the same RBAC/audit pipeline as everything else.
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
  private final ObjectMapper objectMapper;

  /** Remote catalogue slice — tools and sub-agents resolved together from active manifests. */
  private record RemoteCatalog(List<AgentTool> tools, List<AgentTool> agents) {}

  /** Short-lived catalogue cache — same lifetime model as the ChatClient cache. */
  private final Cache<String, RemoteCatalog> remoteCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(1).build();

  public ToolRegistry(
      ModulesService modulesService,
      InstallService installService,
      BuiltinToolHandlers builtinHandlers,
      ObjectMapper objectMapper) {
    this.modulesService = modulesService;
    this.installService = installService;
    this.builtinHandlers = builtinHandlers;
    this.objectMapper = objectMapper;
  }

  /** Built-in portal tools (AI plan B1 seed set — low-risk reads). */
  public List<AgentTool> builtinTools() {
    return builtinHandlers.tools();
  }

  /**
   * Tools contributed by active installed modules (manifest {@code agentContributions.tools[]}).
   */
  public List<AgentTool> remoteTools() {
    return remoteCache.get("catalogue", k -> resolveRemote()).tools();
  }

  /**
   * Sub-agent delegations contributed by active installed modules (manifest {@code
   * agentContributions.agents[]}) — AI plan F2.
   */
  public List<AgentTool> remoteAgents() {
    return remoteCache.get("catalogue", k -> resolveRemote()).agents();
  }

  /** Full catalogue: builtins first, then remote contributions (stable order). */
  public List<AgentTool> catalogue() {
    List<AgentTool> all = new ArrayList<>(builtinTools());
    all.addAll(remoteTools());
    all.addAll(remoteAgents());
    return List.copyOf(all);
  }

  /** Finds a tool by model-facing name ({@code name} or {@code moduleKey_name}). */
  public AgentTool byModelName(String modelName) {
    return catalogue().stream()
        .filter(t -> t.modelName().equals(modelName))
        .findFirst()
        .orElse(null);
  }

  private RemoteCatalog resolveRemote() {
    List<AgentTool> tools = new ArrayList<>();
    List<AgentTool> agents = new ArrayList<>();
    for (ModuleEntity module : modulesService.list(false)) {
      if (module.getBaseUrl() == null || module.getBaseUrl().isBlank()) {
        continue;
      }
      JsonNode manifest = installService.activeManifest(module.getKey());
      if (manifest == null) {
        continue;
      }
      JsonNode contributions = manifest.path("agentContributions");
      JsonNode toolEntries = contributions.path("tools");
      if (toolEntries.isArray()) {
        for (JsonNode tool : toolEntries) {
          String name = tool.path("name").asString(null);
          if (name == null || name.isBlank()) {
            continue;
          }
          tools.add(
              AgentTool.remote(
                  module.getKey(),
                  module.getBaseUrl(),
                  name,
                  tool.path("description").asString(""),
                  tool.has("arguments") ? tool.get("arguments") : null,
                  tool.path("mutates").asBoolean(false),
                  List.copyOf(rolesOf(tool)),
                  tool.hasNonNull("path") ? tool.path("path").asString() : null));
        }
      }
      JsonNode agentEntries = contributions.path("agents");
      if (agentEntries.isArray()) {
        for (JsonNode agent : agentEntries) {
          String name = agent.path("name").asString(null);
          String endpoint = agent.path("endpoint").asString(null);
          if (name == null || name.isBlank() || endpoint == null || !endpoint.startsWith("/")) {
            continue;
          }
          agents.add(
              AgentTool.agent(
                  module.getKey(),
                  module.getBaseUrl(),
                  name,
                  agent.path("description").asString(""),
                  agentArguments(module.getKey(), module.getKey() + "_" + name.replace('-', '_')),
                  List.copyOf(rolesOf(agent)),
                  endpoint));
        }
      }
    }
    log.debug("[agent] remote catalogue: {} tools, {} sub-agents", tools.size(), agents.size());
    return new RemoteCatalog(List.copyOf(tools), List.copyOf(agents));
  }

  private List<String> rolesOf(JsonNode entry) {
    List<String> roles = new ArrayList<>();
    if (entry.path("roles").isArray()) {
      entry.path("roles").forEach(r -> roles.add(r.asString()));
    }
    return roles;
  }

  /**
   * Fixed argument schema for sub-agent delegations (AI plan F1): the model supplies the task; the
   * portal builds the rest of the envelope (context, timeout) at dispatch time.
   */
  private JsonNode agentArguments(String moduleKey, String modelName) {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ObjectNode task = properties.putObject("task");
    task.put("type", "string");
    task.put(
        "description",
        "The task or question for the "
            + modelName
            + " sub-agent. It runs inside the "
            + moduleKey
            + " module with its own data context and answers on that specialty.");
    ObjectNode expected = properties.putObject("expectedOutput");
    expected.put("type", "string");
    expected.put("description", "Optional: describe the shape of the answer you want back.");
    schema.putArray("required").add("task");
    return schema;
  }
}
