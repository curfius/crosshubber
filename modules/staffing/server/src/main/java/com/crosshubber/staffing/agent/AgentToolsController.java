package com.crosshubber.staffing.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.staffing.domain.CandidateProfileEntity;
import com.crosshubber.staffing.domain.MatchingService;
import com.crosshubber.staffing.domain.RfpEntity;
import com.crosshubber.staffing.domain.RfpStatus;
import com.crosshubber.staffing.domain.StaffingService;
import com.crosshubber.staffing.security.AgentPrincipals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Module tool endpoints (AI plan B2/P3 contract): {@code POST /agent/tools/{name}} with body {@code
 * {"tool": name, "arguments": {...}}} and the {@code X-Portal-Agent} identity header. Staffing
 * ships tools only (no sub-agent). {@code create_match_run} is the mutating tool — the portal gates
 * it behind user confirmation.
 */
@RestController
public class AgentToolsController {

  public record ToolRequest(String tool, JsonNode arguments) {}

  private final StaffingService staffingService;
  private final ObjectMapper objectMapper;

  public AgentToolsController(StaffingService staffingService, ObjectMapper objectMapper) {
    this.staffingService = staffingService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/agent/tools/{name}")
  public ObjectNode dispatch(@PathVariable String name, @RequestBody ToolRequest body) {
    JsonNode args = body.arguments() == null ? objectMapper.createObjectNode() : body.arguments();
    try {
      return switch (name) {
        case "list_rfps" -> listRfps(args);
        case "get_rfp" -> getRfp(args);
        case "search_cvs" -> searchCvs(args);
        case "match_candidates" -> matchCandidates(args);
        case "create_match_run" -> createMatchRun(args);
        default -> error("unknown tool: " + name);
      };
    } catch (ResponseStatusException e) {
      return error(e.getReason() != null ? e.getReason() : "tool failed");
    } catch (Exception e) {
      return error(e.getMessage() != null ? e.getMessage() : "tool failed");
    }
  }

  private ObjectNode listRfps(JsonNode args) {
    String status = textOrNull(args, "status");
    RfpStatus filter = status == null ? null : RfpStatus.fromString(status);
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode rfps = out.putArray("rfps");
    for (RfpEntity r : staffingService.listRfps(filter)) {
      rfps.add(toRfpNode(r));
    }
    return out;
  }

  private ObjectNode getRfp(JsonNode args) {
    UUID id = requireId(args, "rfpId");
    return toRfpNode(staffingService.getRfp(id));
  }

  private ObjectNode searchCvs(JsonNode args) {
    String query = textOrNull(args, "query");
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode candidates = out.putArray("candidates");
    for (CandidateProfileEntity c : staffingService.searchCvs(query)) {
      candidates.add(toCandidateNode(c));
    }
    return out;
  }

  private ObjectNode matchCandidates(JsonNode args) {
    UUID rfpId =
        textOrNull(args, "rfpId") == null ? null : UUID.fromString(textOrNull(args, "rfpId"));
    Map<String, Object> requirements;
    if (rfpId != null) {
      requirements = staffingService.getRfp(rfpId).getRequirements();
    } else {
      JsonNode req = args.path("requirements");
      if (!req.isObject()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "either rfpId or requirements is required");
      }
      requirements =
          objectMapper.convertValue(
              req, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
    int topN = args.path("topN").isNumber() ? args.path("topN").asInt() : 0;
    ObjectNode out = objectMapper.createObjectNode();
    ArrayNode matches = out.putArray("matches");
    for (MatchingService.ScoredCandidate scored :
        staffingService.matchCandidates(requirements, topN)) {
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
    int topN = args.path("topN").isNumber() ? args.path("topN").asInt() : 0;
    var run = staffingService.createMatchRun(rfpId, topN, AgentPrincipals.currentName());
    ObjectNode out = objectMapper.createObjectNode();
    out.put("status", "ok");
    out.put("runId", run.getId().toString());
    out.put("rfpId", run.getRfpId().toString());
    out.set("results", objectMapper.valueToTree(run.getResults()));
    return out;
  }

  // ── Payload shaping ──────────────────────────────────────────────────

  private ObjectNode toRfpNode(RfpEntity r) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", r.getId().toString());
    node.put("client", r.getClient());
    node.put("title", r.getTitle());
    node.put("kind", r.getKind());
    node.put("status", r.getStatus().value());
    node.put("deadline", r.getDeadline().toString());
    node.set("requirements", objectMapper.valueToTree(r.getRequirements()));
    return node;
  }

  private ObjectNode toCandidateNode(CandidateProfileEntity c) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", c.getId().toString());
    node.put("name", c.getName());
    node.put("headline", c.getHeadline());
    node.set("skills", objectMapper.valueToTree(c.getSkills()));
    node.put("seniority", c.getSeniority());
    node.set("languages", objectMapper.valueToTree(c.getLanguages()));
    node.put("availability", c.getAvailability());
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
    return List.of("list_rfps", "get_rfp", "search_cvs", "match_candidates", "create_match_run");
  }
}
