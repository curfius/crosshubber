package com.crosshubber.portal.modules.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.i18n.I18nService;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsService;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentsService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.ShellConfigService;
import com.crosshubber.portal.workspaces.WorkspaceEntity;
import com.crosshubber.portal.workspaces.WorkspaceRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handlers for the built-in portal tools (AI plan B1 seed set: low-risk reads). Every handler is a
 * typed call into an existing service with the caller's identity — the agent never gets a privilege
 * bypass. All builtins are reads; mutating tools come from remote modules.
 */
@Service
public class BuiltinToolHandlers {

  private static final int MAX_CONTENT_ROWS = 200;
  private static final int MAX_LABEL_ROWS = 200;
  private static final int DEFAULT_LABEL_LIMIT = 100;
  private static final int MAX_WORKSPACES = 50;

  private final ShellConfigService shellConfigService;
  private final ModulesService modulesService;
  private final ModuleContentsService moduleContentsService;
  private final I18nService i18nService;
  private final WorkspaceRepository workspaceRepository;
  private final NavigationSettingsService navigationSettingsService;
  private final ObjectMapper objectMapper;

  public BuiltinToolHandlers(
      ShellConfigService shellConfigService,
      ModulesService modulesService,
      ModuleContentsService moduleContentsService,
      I18nService i18nService,
      WorkspaceRepository workspaceRepository,
      NavigationSettingsService navigationSettingsService,
      ObjectMapper objectMapper) {
    this.shellConfigService = shellConfigService;
    this.modulesService = modulesService;
    this.moduleContentsService = moduleContentsService;
    this.i18nService = i18nService;
    this.workspaceRepository = workspaceRepository;
    this.navigationSettingsService = navigationSettingsService;
    this.objectMapper = objectMapper;
  }

  /** The built-in tool catalogue, in stable order. */
  public List<AgentTool> tools() {
    List<AgentTool> tools = new ArrayList<>();
    tools.add(
        AgentTool.builtin(
            "getShellConfig",
            "Returns the caller's shell configuration: user identity, visible module contents, "
                + "navigation groups and platform services.",
            null,
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "listModules",
            "Lists installed portal modules with key, name, active/builtin flags and version.",
            null,
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "listModuleContents",
            "Lists module contents (entry points) with category, type and roles. Optionally "
                + "filtered by module key.",
            schema(
                new Arg("moduleKey", "string", "Filter by module key, e.g. ai-hub. Omit for all.")),
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "getI18nLabels",
            "Returns i18n label keys/values for a language, optionally filtered by key prefix. "
                + "Use a prefix — the full bundle is large.",
            schema(
                new Arg(
                    "language",
                    "string",
                    "Language code, e.g. en-GB. Defaults to the tenant default."),
                new Arg("prefix", "string", "Only labels whose key starts with this prefix."),
                new Arg("limit", "integer", "Max labels returned (default 100, max 200).")),
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "listWorkspaces",
            "Lists the caller's saved workspaces (name, description, status).",
            null,
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "getInstanceSettings",
            "Returns instance-level navigation settings (home app, theme policy, feature flags).",
            null,
            false,
            List.of()));
    return List.copyOf(tools);
  }

  /**
   * Executes a built-in tool.
   *
   * @param tool the built-in tool (kind BUILTIN)
   * @param user the caller — handlers see the same identity the HTTP layer would
   * @param args tool arguments, never null
   * @return the tool result payload
   */
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    return switch (tool.name()) {
      case "getShellConfig" -> getShellConfig(user);
      case "listModules" -> listModules();
      case "listModuleContents" -> listModuleContents(args);
      case "getI18nLabels" -> getI18nLabels(args);
      case "listWorkspaces" -> listWorkspaces(user);
      case "getInstanceSettings" -> getInstanceSettings();
      default -> error("unknown builtin tool: " + tool.name());
    };
  }

  private JsonNode getShellConfig(PortalUser user) {
    return objectMapper.valueToTree(shellConfigService.buildConfig(user));
  }

  private JsonNode listModules() {
    ArrayNode out = objectMapper.createArrayNode();
    for (ModuleEntity m : modulesService.list(false)) {
      ObjectNode row = objectMapper.createObjectNode();
      row.put("key", m.getKey());
      row.put("name", m.getName());
      row.put("active", m.getActive());
      row.put("builtin", m.getBuiltin());
      row.put("version", m.getVersion());
      row.put("managedBy", m.getManagedBy());
      out.add(row);
    }
    return out;
  }

  private JsonNode listModuleContents(JsonNode args) {
    String moduleKey = Texts.blankToNull(Texts.string(args.path("moduleKey").asString(null)));
    List<String> keys =
        moduleKey != null
            ? List.of(moduleKey)
            : modulesService.list(false).stream().map(m -> m.getKey()).toList();
    ArrayNode out = objectMapper.createArrayNode();
    boolean truncated = false;
    outer:
    for (String key : keys) {
      for (ModuleContentEntity ep : moduleContentsService.list(key)) {
        if (out.size() >= MAX_CONTENT_ROWS) {
          truncated = true;
          break outer;
        }
        ObjectNode row = objectMapper.createObjectNode();
        row.put("moduleKey", ep.getModuleKey());
        row.put("contentKey", ep.getContentKey());
        row.put("category", ep.getCategory() == null ? null : ep.getCategory().value());
        row.put("name", ep.getName());
        row.put("type", ep.getType() == null ? null : ep.getType().value());
        row.put("active", ep.getActive());
        row.put("hidden", ep.getHidden());
        out.add(row);
      }
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.set("contents", out);
    result.put("truncated", truncated);
    return result;
  }

  private JsonNode getI18nLabels(JsonNode args) {
    String language = Texts.blankToNull(Texts.string(args.path("language").asString(null)));
    String prefix = Texts.blankToNull(Texts.string(args.path("prefix").asString(null)));
    int limit = intArg(args, "limit", DEFAULT_LABEL_LIMIT, 1, MAX_LABEL_ROWS);
    if (language == null) {
      language = i18nService.getSettingsRow().getDefaultLanguage();
    }
    Map<String, String> labels = i18nService.getLabels(language);
    ArrayNode out = objectMapper.createArrayNode();
    int total = 0;
    for (Map.Entry<String, String> e : labels.entrySet()) {
      if (prefix != null && !e.getKey().startsWith(prefix)) {
        continue;
      }
      total++;
      if (out.size() < limit) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("key", e.getKey());
        row.put("value", e.getValue());
        out.add(row);
      }
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.put("language", language);
    result.put("total", total);
    result.put("returned", out.size());
    result.set("labels", out);
    return result;
  }

  private JsonNode listWorkspaces(PortalUser user) {
    ArrayNode out = objectMapper.createArrayNode();
    for (WorkspaceEntity w : workspaceRepository.findByUserIdOrderBySavedAtDesc(user.sub())) {
      if (out.size() >= MAX_WORKSPACES) {
        break;
      }
      ObjectNode row = objectMapper.createObjectNode();
      row.put("name", w.getName());
      row.put("description", w.getDescription());
      row.put("status", w.getStatus());
      row.put("savedAt", w.getSavedAt() == null ? null : w.getSavedAt().toString());
      out.add(row);
    }
    return out;
  }

  private JsonNode getInstanceSettings() {
    return objectMapper.valueToTree(navigationSettingsService.get());
  }

  private ObjectNode error(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }

  private static int intArg(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args.path(field);
    if (!node.isNumber()) {
      return fallback;
    }
    return Math.max(min, Math.min(max, node.asInt(fallback)));
  }

  // ── Argument schema helpers ──────────────────────────────────────────

  /** Named JSON Schema property definition for tool argument schemas. */
  private record Arg(String name, String type, String description) {}

  /** Builds a JSON Schema object node from named property definitions. */
  private ObjectNode schema(Arg... args) {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    for (Arg arg : args) {
      ObjectNode prop = properties.putObject(arg.name());
      prop.put("type", arg.type());
      prop.put("description", arg.description());
    }
    return schema;
  }
}
