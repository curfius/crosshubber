package com.crosshubber.solutions.agent;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.solutions.domain.ProjectEntity;
import com.crosshubber.solutions.domain.ProjectService;
import com.crosshubber.solutions.domain.Stage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Module-owned sub-agent endpoint (AI plan F1 envelope): {@code POST /agent/tasks} with the
 * portal-issued agent-call token. The Projects Agent answers delivery-status questions from live
 * project data. Returns {@code {status, output, artifacts[], auditRef}}; LLM-unconfigured → {@code
 * status:"failed"} (fail-closed).
 */
@RestController
public class AgentTasksController {

  /** F1 task envelope (portal orchestrator contract). */
  public record TaskEnvelope(
      String task, JsonNode context, String expectedOutput, JsonNode constraints, Long timeoutMs) {}

  public record TaskResult(
      String status, String output, java.util.List<String> artifacts, String auditRef) {}

  private final ProjectService projectService;
  private final LlmClient llm;
  private final ObjectMapper objectMapper;

  public AgentTasksController(
      ProjectService projectService, LlmClient llm, ObjectMapper objectMapper) {
    this.projectService = projectService;
    this.llm = llm;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/agent/tasks")
  public TaskResult tasks(@RequestBody TaskEnvelope envelope) {
    if (envelope.task() == null || envelope.task().isBlank()) {
      return new TaskResult("failed", "task is required", java.util.List.of(), null);
    }
    if (!llm.isConfigured()) {
      return new TaskResult(
          "failed", "llm is not configured on the solutions module", java.util.List.of(), null);
    }
    try {
      String system =
          "You are the Solutions module agent (Crosshubber portal). You answer questions about "
              + "project delivery status using ONLY the live project data provided. Be concise.";
      String projectsContext = buildProjectsContext();
      String user =
          envelope.task()
              + "\n\nLive project data (JSON):\n"
              + projectsContext
              + (envelope.context() != null
                  ? "\n\nPortal context:\n" + objectMapper.writeValueAsString(envelope.context())
                  : "");
      String output = llm.complete(system, user);
      return new TaskResult("done", output, java.util.List.of(), null);
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "task failed";
      return new TaskResult("failed", message, java.util.List.of(), null);
    }
  }

  private String buildProjectsContext() {
    var projects =
        projectService.listProjects(null, null, null).stream().map(this::toSummary).toList();
    return objectMapper.writeValueAsString(projects);
  }

  private Map<String, Object> toSummary(ProjectEntity p) {
    return Map.of(
        "id",
        p.getId().toString(),
        "name",
        p.getName(),
        "client",
        p.getClient() == null ? "" : p.getClient().getName(),
        "stage",
        p.getStage().value(),
        "health",
        p.getHealth() == null ? "" : p.getHealth(),
        "owner",
        p.getOwner() == null ? "" : p.getOwner(),
        "inImplementation",
        p.getStage() == Stage.IMPLEMENTATION);
  }
}
