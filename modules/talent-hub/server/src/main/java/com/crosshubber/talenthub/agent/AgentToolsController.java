package com.crosshubber.talenthub.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.domain.AnalyticsService;
import com.crosshubber.talenthub.domain.CandidateProcessService;
import com.crosshubber.talenthub.domain.CandidateService;
import com.crosshubber.talenthub.domain.MatchRunEntity;
import com.crosshubber.talenthub.domain.MatchRunService;
import com.crosshubber.talenthub.domain.MatchingService;
import com.crosshubber.talenthub.domain.RfpCaptureService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.domain.RfpPhase;
import com.crosshubber.talenthub.domain.RfpQueryService;
import com.crosshubber.talenthub.domain.RfpWorkflowService;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Module tool endpoints (AI plan B2/P3 contract): {@code POST /agent/tools/{name}} with body {@code
 * {"tool": name, "arguments": {...}}} and the {@code X-Portal-Agent} identity header. Talent Hub
 * ships tools only (no sub-agent). Manifest role gating happens in the portal dispatcher; the
 * settings toggle gate runs here. Tool list grows with the manifest (test asserts sync).
 */
@RestController
public class AgentToolsController {

  public record ToolRequest(String tool, JsonNode arguments) {}

  private final ModuleSettingsService settings;
  private final RfpCaptureService captureService;
  private final RfpWorkflowService workflowService;
  private final RfpQueryService queryService;
  private final CandidateProcessService processService;
  private final CandidateService candidateService;
  private final MatchRunService matchRunService;
  private final AnalyticsService analyticsService;
  private final ObjectMapper objectMapper;

  public AgentToolsController(
      ModuleSettingsService settings,
      RfpCaptureService captureService,
      RfpWorkflowService workflowService,
      RfpQueryService queryService,
      CandidateProcessService processService,
      CandidateService candidateService,
      MatchRunService matchRunService,
      AnalyticsService analyticsService,
      ObjectMapper objectMapper) {
    this.settings = settings;
    this.captureService = captureService;
    this.workflowService = workflowService;
    this.queryService = queryService;
    this.processService = processService;
    this.candidateService = candidateService;
    this.matchRunService = matchRunService;
    this.analyticsService = analyticsService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/agent/tools/{name}")
  public ObjectNode dispatch(@PathVariable String name, @RequestBody ToolRequest body) {
    if (!settings.isToolEnabled(name)) {
      return error("tool \"" + name + "\" is disabled in the talent-hub module settings");
    }
    JsonNode args = body.arguments() == null ? objectMapper.createObjectNode() : body.arguments();
    try {
      return switch (name) {
        case "list_rfps" -> listRfps(args);
        case "get_rfp" -> getRfp(args);
        case "create_rfp" -> createRfp(args);
        case "approve_rfp" -> approveRfp(args);
        case "list_candidate_processes" -> listCandidateProcesses(args);
        case "search_candidates" -> searchCandidates(args);
        case "open_candidate_process" -> openCandidateProcess(args);
        case "advance_process_stage" -> advanceProcessStage(args);
        case "close_rfp" -> closeRfp(args);
        case "assess_fit" -> assessFit(args);
        case "match_candidates" -> matchCandidates(args);
        case "create_match_run" -> createMatchRun(args);
        case "get_rfp_analytics" -> getRfpAnalytics(args);
        default -> error("unknown tool: " + name);
      };
    } catch (ResponseStatusException e) {
      return error(e.getReason() != null ? e.getReason() : "tool failed");
    } catch (Exception e) {
      return error(e.getMessage() != null ? e.getMessage() : "tool failed");
    }
  }

  private ObjectNode listRfps(JsonNode args) {
    String phase = textOrNull(args, "phase");
    RfpPhase phaseFilter = phase == null ? null : RfpPhase.fromString(phase);
    UUID clientId = uuidOrNull(textOrNull(args, "clientId"));
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode rfps = out.putArray("rfps");
    for (RfpDtos.RfpDto rfp :
        queryService.list(
            phaseFilter, clientId, textOrNull(args, "serviceType"), textOrNull(args, "kind"))) {
      rfps.add(rfpNode(rfp));
    }
    return out;
  }

  private ObjectNode getRfp(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    RfpDtos.RfpDetailDto detail = queryService.detail(rfpId);
    ObjectNode node = rfpNode(detail.rfp());
    node.set("client", objectMapper.valueToTree(detail.client()));
    node.set("positions", objectMapper.valueToTree(detail.positions()));
    node.set("processes", objectMapper.valueToTree(detail.processes()));
    node.set("submissions", objectMapper.valueToTree(detail.submissions()));
    node.set("phaseHistory", objectMapper.valueToTree(detail.phaseHistory()));
    return node;
  }

  private ObjectNode createRfp(JsonNode args) {
    RfpCaptureService.NewClient newClient = null;
    JsonNode newClientNode = args.path("newClient");
    if (newClientNode.isObject()) {
      newClient =
          new RfpCaptureService.NewClient(
              textOrNull(newClientNode, "name"),
              textOrNull(newClientNode, "industry"),
              textOrNull(newClientNode, "accountOwner"));
    }
    RfpCaptureService.CaptureRequest request =
        new RfpCaptureService.CaptureRequest(
            textOrNull(args, "clientRef"),
            newClient,
            textOrNull(args, "kind"),
            textOrNull(args, "serviceType"),
            textOrNull(args, "title"),
            textOrNull(args, "deadline"),
            textOrNull(args, "description"),
            stringListOrEmpty(args, "documentRefs"));
    return rfpNode(captureService.capture(request, AgentPrincipals.currentName()));
  }

  private ObjectNode approveRfp(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    RfpWorkflowService.WorkflowResult result =
        workflowService.approve(rfpId, AgentPrincipals.currentName());
    return rfpNode(RfpDtos.toDto(result.rfp()));
  }

  private ObjectNode listCandidateProcesses(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode processes = out.putArray("processes");
    for (RfpDtos.ProcessDto process : queryService.detail(rfpId).processes()) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("id", process.id().toString());
      node.put("positionId", process.positionId().toString());
      node.put("candidateId", process.candidateId().toString());
      node.put("stage", process.stage());
      processes.add(node);
    }
    return out;
  }

  private ObjectNode searchCandidates(JsonNode args) {
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode candidates = out.putArray("candidates");
    for (CandidateService.CandidateDto candidate :
        candidateService.list(
            textOrNull(args, "query"),
            textOrNull(args, "kind"),
            textOrNull(args, "availability"),
            Boolean.TRUE.equals(args.path("staleOnly").asBoolean(false)))) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("id", candidate.id().toString());
      node.put("name", candidate.name());
      node.put("kind", candidate.kind());
      node.put("seniority", candidate.seniority());
      node.set("skills", objectMapper.valueToTree(candidate.skills()));
      node.put("stale", candidate.stale());
      candidates.add(node);
    }
    return out;
  }

  private ObjectNode openCandidateProcess(JsonNode args) {
    UUID positionId = requireId(args, "positionId");
    UUID candidateId = requireId(args, "candidateId");
    RfpDtos.ProcessDto process =
        processService.open(
            positionId,
            new CandidateProcessService.OpenProcessRequest(candidateId, textOrNull(args, "note")),
            AgentPrincipals.currentName());
    return processNode(process);
  }

  private ObjectNode advanceProcessStage(JsonNode args) {
    UUID processId = requireId(args, "processId");
    RfpDtos.ProcessDto process =
        processService.transition(
            processId,
            new CandidateProcessService.StageChangeRequest(
                textOrNull(args, "toStage"), textOrNull(args, "note")),
            AgentPrincipals.currentName());
    return processNode(process);
  }

  private ObjectNode closeRfp(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    boolean confirm = Boolean.TRUE.equals(args.path("confirm").asBoolean(false));
    RfpWorkflowService.WorkflowResult result =
        workflowService.close(
            rfpId, confirm, textOrNull(args, "reason"), AgentPrincipals.currentName());
    ObjectNode node = rfpNode(RfpDtos.toDto(result.rfp()));
    node.put("cancelledProcesses", result.cancelledProcessIds().size());
    return node;
  }

  /** Runs the configured (or overridden) fit engine on a POOLED process (P3.2). */
  private ObjectNode assessFit(JsonNode args) {
    UUID processId = requireId(args, "processId");
    RfpDtos.ProcessDto process =
        processService.assess(
            processId,
            new CandidateProcessService.AssessRequest(textOrNull(args, "engine")),
            AgentPrincipals.currentName());
    ObjectNode node = processNode(process);
    node.set("fit", objectMapper.valueToTree(process.fit()));
    return node;
  }

  private ObjectNode matchCandidates(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    Map<String, Object> requirements = queryService.detail(rfpId).rfp().requirements();
    int topN = args.path("topN").isNumber() ? args.path("topN").asInt() : 5;
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode matches = out.putArray("matches");
    for (MatchingService.ScoredCandidate scored : matchRunService.rank(requirements, topN)) {
      ObjectNode row = matches.addObject();
      row.put("candidateId", scored.candidateId().toString());
      row.put("name", scored.name());
      row.put("score", scored.score());
      row.put("rationale", scored.rationale());
    }
    return out;
  }

  private ObjectNode createMatchRun(JsonNode args) {
    UUID rfpId = requireId(args, "rfpId");
    MatchRunEntity run =
        matchRunService.createMatchRun(
            rfpId,
            args.path("topN").isNumber() ? args.path("topN").asInt() : null,
            textOrNull(args, "engine"),
            AgentPrincipals.currentName());
    ObjectNode out = objectMapper.createObjectNode();
    out.put("status", "ok");
    out.put("runId", run.getId().toString());
    out.put("rfpId", run.getRfpId().toString());
    out.put("engine", run.getEngine());
    out.set("results", objectMapper.valueToTree(run.getResults()));
    return out;
  }

  private ObjectNode getRfpAnalytics(JsonNode args) {
    AnalyticsService.AnalyticsSummary summary =
        analyticsService.summary(textOrNull(args, "period"));
    return (ObjectNode) objectMapper.valueToTree(summary);
  }

  // ── Payload shaping ──────────────────────────────────────────────────

  private ObjectNode rfpNode(RfpDtos.RfpDto rfp) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", rfp.id().toString());
    node.put("clientId", rfp.clientId().toString());
    node.put("title", rfp.title());
    node.put("kind", rfp.kind());
    node.put("phase", rfp.phase());
    node.put("serviceType", rfp.serviceType());
    node.put("source", rfp.source());
    if (rfp.deadline() != null) {
      node.put("deadline", rfp.deadline());
    }
    node.put("description", rfp.description());
    node.set("requirements", objectMapper.valueToTree(rfp.requirements()));
    if (rfp.analysis() != null) {
      node.set("analysis", objectMapper.valueToTree(rfp.analysis()));
    }
    node.set("documentRefs", objectMapper.valueToTree(rfp.documentRefs()));
    node.put("createdBy", rfp.createdBy());
    return node;
  }

  private ObjectNode processNode(RfpDtos.ProcessDto process) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", process.id().toString());
    node.put("rfpId", process.rfpId().toString());
    node.put("positionId", process.positionId().toString());
    node.put("candidateId", process.candidateId().toString());
    node.put("stage", process.stage());
    return node;
  }

  private UUID requireId(JsonNode args, String field) {
    String raw = textOrNull(args, field);
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID");
    }
  }

  private UUID uuidOrNull(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static List<String> stringListOrEmpty(JsonNode args, String field) {
    JsonNode node = args.path(field);
    if (!node.isArray()) {
      return List.of();
    }
    List<String> out = new java.util.ArrayList<>();
    node.forEach(
        item -> {
          if (item.isString() && !item.asString().isBlank()) {
            out.add(item.asString());
          }
        });
    return out;
  }

  private static String textOrNull(JsonNode args, String field) {
    JsonNode node = args.path(field);
    if (!node.isString()) {
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
        "list_rfps",
        "get_rfp",
        "create_rfp",
        "approve_rfp",
        "list_candidate_processes",
        "search_candidates",
        "open_candidate_process",
        "advance_process_stage",
        "close_rfp",
        "assess_fit",
        "match_candidates",
        "create_match_run",
        "get_rfp_analytics");
  }
}
