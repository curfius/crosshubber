package com.crosshubber.solutions.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.solutions.domain.ProjectEntity;
import com.crosshubber.solutions.domain.ProjectService;
import com.crosshubber.solutions.domain.Stage;
import com.crosshubber.solutions.domain.StageEventEntity;
import com.crosshubber.solutions.security.AgentPrincipal;
import com.crosshubber.solutions.security.AgentPrincipals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Module tool endpoints (AI plan B2/P3 contract): {@code POST /agent/tools/{name}} with body {@code
 * {"tool": name, "arguments": {...}}} and the {@code X-Portal-Agent} identity header. Returns the
 * result payload JSON directly; tool-level failures return {@code {"error": "..."}}. Every call
 * runs as the token's user — no elevation.
 */
@RestController
public class AgentToolsController {

  private final ProjectService projectService;
  private final ObjectMapper objectMapper;

  public AgentToolsController(ProjectService projectService, ObjectMapper objectMapper) {
    this.projectService = projectService;
    this.objectMapper = objectMapper;
  }

  /** Remote tool request envelope (portal dispatch contract). */
  public record ToolRequest(String tool, JsonNode arguments) {}

  @PostMapping("/agent/tools/{name}")
  public ObjectNode dispatch(@PathVariable String name, @RequestBody ToolRequest body) {
    AgentPrincipal user = AgentPrincipals.current();
    JsonNode args = body.arguments() == null ? objectMapper.createObjectNode() : body.arguments();
    try {
      return switch (name) {
        case "list_projects" -> listProjects(args);
        case "get_project" -> getProject(args);
        case "get_stage_history" -> stageHistory(args);
        case "update_project_stage" -> updateProjectStage(user, args);
        case "search_project_docs" -> searchProjectDocs(args);
        default -> error("unknown tool: " + name);
      };
    } catch (ResponseStatusException e) {
      return error(e.getReason() != null ? e.getReason() : "tool failed");
    } catch (Exception e) {
      return error(e.getMessage() != null ? e.getMessage() : "tool failed");
    }
  }

  private ObjectNode listProjects(JsonNode args) {
    Stage stage =
        args.path("stage").isMissingNode() || args.path("stage").isNull()
            ? null
            : Stage.fromString(args.path("stage").asString(null));
    String health = textOrNull(args, "health");
    UUID clientId =
        textOrNull(args, "clientId") == null ? null : UUID.fromString(textOrNull(args, "clientId"));
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode projects = out.putArray("projects");
    for (ProjectEntity p : projectService.listProjects(stage, health, clientId)) {
      projects.add(toProjectNode(p));
    }
    return out;
  }

  private ObjectNode getProject(JsonNode args) {
    UUID id = requireId(args);
    return toProjectNode(projectService.getProject(id));
  }

  private ObjectNode stageHistory(JsonNode args) {
    UUID id = requireId(args);
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode events = out.putArray("events");
    for (StageEventEntity e : projectService.stageHistory(id)) {
      ObjectNode row = events.addObject();
      row.put("from", e.getFromStage());
      row.put("to", e.getToStage());
      row.put("actor", e.getActor());
      row.put("note", e.getNote());
      row.put("occurredAt", e.getOccurredAt().toString());
    }
    return out;
  }

  private ObjectNode updateProjectStage(AgentPrincipal user, JsonNode args) {
    UUID id = requireId(args);
    String toStage = textOrNull(args, "toStage");
    if (toStage == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toStage is required");
    }
    String note = textOrNull(args, "note");
    JsonNode patch = args.path("stageData");
    Map<String, Object> stageData =
        patch.isObject()
            ? objectMapper.convertValue(
                patch, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {})
            : null;
    ProjectEntity updated = projectService.transition(id, toStage, user.name(), note, stageData);
    ObjectNode out = objectMapper.createObjectNode();
    out.put("status", "ok");
    out.set("project", toProjectNode(updated));
    return out;
  }

  private ObjectNode searchProjectDocs(JsonNode args) {
    UUID id = requireId(args);
    String query = textOrNull(args, "query");
    if (query == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
    }
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode snippets = out.putArray("snippets");
    for (Map<String, Object> row : projectService.searchProjectDocs(id, query)) {
      ObjectNode node = snippets.addObject();
      row.forEach(node::putPOJO);
    }
    return out;
  }

  // ── Payload shaping ──────────────────────────────────────────────────

  private ObjectNode toProjectNode(ProjectEntity p) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", p.getId().toString());
    node.put("name", p.getName());
    node.put("client", p.getClient() == null ? null : p.getClient().getName());
    node.put("stage", p.getStage().value());
    node.put("owner", p.getOwner());
    node.put("budget", p.getBudget());
    node.put("health", p.getHealth());
    node.set("stageData", objectMapper.valueToTree(p.getStageData()));
    node.put("startedAt", p.getStartedAt() == null ? null : p.getStartedAt().toString());
    node.put("closedAt", p.getClosedAt() == null ? null : p.getClosedAt().toString());
    return node;
  }

  private UUID requireId(JsonNode args) {
    String raw = textOrNull(args, "projectId");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId must be a UUID");
    }
  }

  private static String textOrNull(JsonNode args, String field) {
    JsonNode node = args.path(field);
    if (!node.isTextual()) {
      return null;
    }
    String value = node.asString();
    return value == null || value.isBlank() ? null : value;
  }

  private ObjectNode error(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }

  /** Declared tool names — kept in sync with the module manifest (tests assert this). */
  public static List<String> toolNames() {
    return List.of(
        "list_projects",
        "get_project",
        "get_stage_history",
        "update_project_stage",
        "search_project_docs");
  }
}
