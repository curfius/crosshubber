package com.crosshubber.portal.modules.registry.agent;

import static com.crosshubber.portal.modules.aihub.agent.ToolSchemas.arg;
import static com.crosshubber.portal.modules.aihub.agent.ToolSchemas.schema;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentsService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Registry agent tools (AI plan B1 seeds, D5 provider split): declared against the {@code
 * module-registry} embedded module, so deactivating the registry hides them from the agent
 * catalogue.
 */
@Service
public class RegistryAgentTools implements BuiltinToolProvider {

  static final String MODULE_KEY = "module-registry";

  private static final int MAX_CONTENT_ROWS = 200;

  private final ModulesService modulesService;
  private final ModuleContentsService moduleContentsService;
  private final ObjectMapper objectMapper;

  public RegistryAgentTools(
      ModulesService modulesService,
      ModuleContentsService moduleContentsService,
      ObjectMapper objectMapper) {
    this.modulesService = modulesService;
    this.moduleContentsService = moduleContentsService;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AgentTool> tools() {
    return List.of(
        AgentTool.builtin(
            MODULE_KEY,
            "listModules",
            "Lists installed portal modules with key, name, active/builtin flags and version.",
            null,
            false,
            List.of()),
        AgentTool.builtin(
            MODULE_KEY,
            "listModuleContents",
            "Lists module contents (entry points) with category, type and roles. Optionally "
                + "filtered by module key.",
            schema(
                objectMapper,
                arg("moduleKey", "string", "Filter by module key, e.g. ai-hub. Omit for all.")),
            false,
            List.of()));
  }

  @Override
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    return switch (tool.name()) {
      case "listModules" -> listModules();
      case "listModuleContents" -> listModuleContents(args);
      default -> error("unknown registry tool: " + tool.name());
    };
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

  private ObjectNode error(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }
}
