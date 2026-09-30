package com.crosshubber.portal.modules.agent;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * A tool callable by the model (or another agent) through the dispatcher. Built-in tools are
 * portal-native reads; remote tools are contributed by installed modules via the manifest {@code
 * agentContributions.tools[]} (AI plan B1/B2/D3); agents are manifest {@code
 * agentContributions.agents[]} entries — delegations to a module-owned sub-agent over the F1 task
 * envelope (AI plan F, AI_MODULES_PLAN P7).
 *
 * @param id unique registry id ({@code <name>} for builtin, {@code <moduleKey>:<name>} for remote)
 * @param modelName name exposed to the model — must be provider-safe, so remote entries are
 *     flattened to {@code <moduleKey>_<name>} with {@code -} replaced by {@code _} (dots/colons are
 *     not portable across function-calling providers)
 * @param moduleKey owning module key, null for built-in tools
 * @param name tool name as declared (manifest name, or registry name for builtins)
 * @param description what the tool does — shown to the model
 * @param arguments JSON Schema for the arguments object, null when the tool takes no arguments
 * @param mutates true when the tool changes state — requires the confirmation flow (AI plan B6)
 * @param roles required portal roles; empty = any authenticated user (AI plan B4)
 * @param kind BUILTIN, REMOTE or AGENT
 * @param baseUrl remote/agent only: the owning module's installed base URL
 * @param path remote-only: endpoint path override, defaults to {@code /agent/tools/{name}};
 *     agent-only: the module's task endpoint (manifest {@code endpoint}, e.g. {@code /agent/tasks})
 */
public record AgentTool(
    String id,
    String modelName,
    String moduleKey,
    String name,
    String description,
    JsonNode arguments,
    boolean mutates,
    List<String> roles,
    Kind kind,
    String baseUrl,
    String path) {

  /** Tool origin. */
  public enum Kind {
    BUILTIN,
    REMOTE,
    AGENT
  }

  public static AgentTool builtin(
      String name, String description, JsonNode arguments, boolean mutates, List<String> roles) {
    return new AgentTool(
        name, name, null, name, description, arguments, mutates, roles, Kind.BUILTIN, null, null);
  }

  public static AgentTool remote(
      String moduleKey,
      String baseUrl,
      String name,
      String description,
      JsonNode arguments,
      boolean mutates,
      List<String> roles,
      String path) {
    return new AgentTool(
        moduleKey + ":" + name,
        moduleKey + "_" + name.replace('-', '_'),
        moduleKey,
        name,
        description,
        arguments,
        mutates,
        roles,
        Kind.REMOTE,
        baseUrl,
        path);
  }

  /**
   * A delegation entry for a module-owned sub-agent (AI plan F2/F3). Always read-like from the
   * portal's perspective: the sub-agent decides internally what it does, so no confirmation parking
   * applies — mutation safety relies on the manifest {@code roles[]} gate enforced by the
   * dispatcher.
   */
  public static AgentTool agent(
      String moduleKey,
      String baseUrl,
      String name,
      String description,
      JsonNode arguments,
      List<String> roles,
      String endpoint) {
    return new AgentTool(
        moduleKey + ":" + name,
        moduleKey + "_" + name.replace('-', '_'),
        moduleKey,
        name,
        description,
        arguments,
        false,
        roles,
        Kind.AGENT,
        baseUrl,
        endpoint);
  }
}
