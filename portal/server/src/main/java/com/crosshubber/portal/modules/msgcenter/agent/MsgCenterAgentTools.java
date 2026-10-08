package com.crosshubber.portal.modules.msgcenter.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService;
import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto;
import com.crosshubber.portal.modules.msgcenter.publish.MsgCenterPublishService;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterResponsePublisher;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterTaskService;
import com.crosshubber.portal.modules.msgcenter.tasks.SubmitDataValidator;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Message-center portal agent tools (MESSAGE_CENTER plan §12, Phase 6) — registered in the same
 * builtin catalogue as the B1 seeds; dispatch, confirmation parking and audit come free from {@code
 * ToolDispatcher}. Identity is the session user (audience gates apply — the agent never sees beyond
 * the caller's inbox); {@code msgcenter_send} reuses the exact publish path of the HTTP endpoint
 * (validators, template resolution, moduleKey check) minus the secret header.
 */
@Service
public class MsgCenterAgentTools {

  private static final int MAX_LIST_ROWS = 50;

  private final MsgCenterQueryService queryService;
  private final MsgCenterTaskService taskService;
  private final MsgCenterResponsePublisher responsePublisher;
  private final MsgCenterPublishService publishService;
  private final SubmitDataValidator dataValidator;
  private final ObjectMapper objectMapper;

  public MsgCenterAgentTools(
      MsgCenterQueryService queryService,
      MsgCenterTaskService taskService,
      MsgCenterResponsePublisher responsePublisher,
      MsgCenterPublishService publishService,
      SubmitDataValidator dataValidator,
      ObjectMapper objectMapper) {
    this.queryService = queryService;
    this.taskService = taskService;
    this.responsePublisher = responsePublisher;
    this.publishService = publishService;
    this.dataValidator = dataValidator;
    this.objectMapper = objectMapper;
  }

  /** The msgcenter tool catalogue (stable order: reads first, then mutating). */
  public List<AgentTool> tools() {
    List<AgentTool> tools = new ArrayList<>();
    tools.add(
        AgentTool.builtin(
            "msgcenter_list",
            "Lists the caller's Message Center inbox (notifications, messages, tasks) with read "
                + "and status flags. Audience gating applies — this is exactly what the caller "
                + "sees in their inbox.",
            objectSchema(
                arg("type", "string", "Filter: notification | message | task. Omit for all."),
                arg("status", "string", "Task filter: open | claimed | done."),
                arg("unreadOnly", "boolean", "When true, return only items without a read marker."),
                arg("limit", "integer", "Max rows (default 20, max 50).")),
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "msgcenter_get",
            "Returns one inbox item by id: title, body sections, deep-link and the full task "
                + "spec (fields, claim state) plus the caller's own response for completed tasks.",
            objectSchema(arg("id", "integer", "The inbox item id.")),
            false,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "msgcenter_claim_task",
            "Takes an unclaimed claim-mode task on the caller's behalf (CAS: one winner, losers "
                + "get a 409-style error). Returns any released draft available for adoption.",
            objectSchema(arg("id", "integer", "The task id.")),
            true,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "msgcenter_respond_task",
            "Responds to a task: outcome approve|deny|submit|skip, optional validated form data "
                + "(collect fields) and note. Same validation and CAS rules as the HTTP endpoint.",
            objectSchema(
                arg("id", "integer", "The task id."),
                arg("outcome", "string", "approve | deny | submit | skip."),
                arg(
                    "data",
                    "object",
                    "Form data for collect tasks (validated against the task schema)."),
                arg("note", "string", "Optional note.")),
            true,
            List.of()));
    tools.add(
        AgentTool.builtin(
            "msgcenter_send",
            "Publishes a portal message/notification/task envelope on the caller's behalf. The "
                + "envelope must match the v1 contract (moduleKey, audience, title, body, task "
                + "shape or task.template{key,version}); same validators and caps as the publish "
                + "endpoint.",
            objectSchema(arg("envelope", "object", "The complete v1 envelope object.")),
            true,
            List.of()));
    return List.copyOf(tools);
  }

  /** Executes a msgcenter builtin tool for the caller. */
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    return switch (tool.name()) {
      case "msgcenter_list" -> list(user, args);
      case "msgcenter_get" -> get(user, args);
      case "msgcenter_claim_task" -> claim(user, args);
      case "msgcenter_respond_task" -> respond(user, args);
      case "msgcenter_send" -> send(user, args);
      default -> error("unknown msgcenter tool: " + tool.name());
    };
  }

  private JsonNode list(PortalUser user, JsonNode args) {
    String type = stringArg(args, "type");
    String status = stringArg(args, "status");
    boolean unreadOnly = args.path("unreadOnly").asBoolean(false);
    int limit = intArg(args, "limit", 20, 1, MAX_LIST_ROWS);
    List<InboxItemDto> items =
        queryService.listOwn(user.sub(), user.roles(), type, status, null, null, limit);
    ArrayNode out = objectMapper.createArrayNode();
    int returned = 0;
    for (InboxItemDto item : items) {
      if (unreadOnly && item.read()) {
        continue;
      }
      if (returned >= limit) {
        break;
      }
      returned++;
      out.add(objectMapper.valueToTree(item));
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.set("items", out);
    result.put("returned", returned);
    result.put("unread", queryService.unread(user.sub(), user.roles()));
    return result;
  }

  private JsonNode get(PortalUser user, JsonNode args) {
    Long id = longArg(args, "id");
    if (id == null) {
      return error("id is required");
    }
    InboxItemDto item = queryService.getOwn(user.sub(), user.roles(), id);
    if (item == null) {
      return error("message not visible: " + id);
    }
    return objectMapper.valueToTree(item);
  }

  private JsonNode claim(PortalUser user, JsonNode args) {
    Long id = longArg(args, "id");
    if (id == null) {
      return error("id is required");
    }
    if (!queryService.visible(user.sub(), user.roles(), id)) {
      return error("message not visible: " + id);
    }
    var info = taskService.claim(id, user.sub(), user.name());
    ObjectNode result = objectMapper.createObjectNode();
    result.put("claimed", true);
    result.put("fromName", info.fromName());
    result.put("savedAt", info.savedAt());
    if (info.data() != null) {
      result.set("draft", objectMapper.valueToTree(info.data()));
    }
    return result;
  }

  private JsonNode respond(PortalUser user, JsonNode args) {
    Long id = longArg(args, "id");
    if (id == null) {
      return error("id is required");
    }
    String outcome = args.path("outcome").asString(null);
    if (outcome == null || outcome.isBlank()) {
      return error("outcome is required (approve | deny | submit | skip)");
    }
    if (!queryService.visible(user.sub(), user.roles(), id)) {
      return error("message not visible: " + id);
    }
    JsonNode data = args.get("data");
    String note = stringArg(args, "note");
    var result =
        taskService.respond(id, user.sub(), user.name(), outcome, data, note, dataValidator);
    if (result.closed()) {
      responsePublisher.publishResponse(result.message(), result.response());
    }
    ObjectNode out = objectMapper.createObjectNode();
    out.put("status", result.message().getStatus());
    out.put("closed", result.closed());
    out.put("outcome", result.response().getOutcome());
    return out;
  }

  private JsonNode send(PortalUser user, JsonNode args) {
    JsonNode envelope = args.get("envelope");
    if (envelope == null || !envelope.isObject()) {
      return error("envelope object is required");
    }
    long seq = publishService.publish(user.sub(), envelope);
    ObjectNode out = objectMapper.createObjectNode();
    out.put("published", true);
    out.put("streamSeq", seq);
    return out;
  }

  private static String stringArg(JsonNode args, String field) {
    JsonNode node = args.path(field);
    return node.isString() && !node.asString().isBlank() ? node.asString() : null;
  }

  private static Long longArg(JsonNode args, String field) {
    JsonNode node = args.path(field);
    return node.isIntegralNumber() ? node.asLong() : null;
  }

  private static int intArg(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args.path(field);
    if (!node.isNumber()) {
      return fallback;
    }
    return Math.max(min, Math.min(max, node.asInt(fallback)));
  }

  private JsonNode error(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }

  // ── Argument schema helpers ──────────────────────────────────────────

  private record Arg(String name, String type, String description) {}

  private static Arg arg(String name, String type, String description) {
    return new Arg(name, type, description);
  }

  private ObjectNode objectSchema(Arg... args) {
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
