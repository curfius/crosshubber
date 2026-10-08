package com.crosshubber.portal.modules.msgcenter.web;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseRepository;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterResponsePublisher;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterTaskService;
import com.crosshubber.portal.modules.msgcenter.tasks.SubmitDataValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task lifecycle endpoints (plan §6): respond / claim / release / reset / draft / adopt. All
 * session-identity based; admin overrides live in the admin controller. Response-event publishes
 * happen here, AFTER the service transaction commits.
 */
@RestController
public class MsgCenterTaskController {

  private final MsgCenterTaskService taskService;
  private final MsgCenterResponsePublisher responsePublisher;
  private final McTaskResponseRepository responseRepo;
  private final SubmitDataValidator dataValidator;
  private final ObjectMapper mapper;

  public MsgCenterTaskController(
      MsgCenterTaskService taskService,
      MsgCenterResponsePublisher responsePublisher,
      McTaskResponseRepository responseRepo,
      SubmitDataValidator dataValidator,
      ObjectMapper mapper) {
    this.taskService = taskService;
    this.responsePublisher = responsePublisher;
    this.responseRepo = responseRepo;
    this.dataValidator = dataValidator;
    this.mapper = mapper;
  }

  /** Submit a response (approve/deny/submit/skip + optional data + note). */
  @PostMapping("/api/msgcenter/tasks/{id}/respond")
  public Map<String, Object> respond(@PathVariable long id, @RequestBody JsonNode body) {
    String outcome = stringOf(body, "outcome");
    if (outcome == null) {
      outcome = "submit";
    }
    String actorSub = SecurityUtils.currentUserSub();
    String actorName = displayName();
    var result =
        taskService.respond(
            id,
            actorSub,
            actorName,
            outcome,
            body.get("data"),
            stringOf(body, "note"),
            dataValidator);
    if (result.closed()) {
      responsePublisher.publishResponse(result.message(), result.response());
    }
    return Map.of(
        "id", result.response().getId(),
        "outcome", result.response().getOutcome(),
        "status", result.message().getStatus(),
        "closed", result.closed());
  }

  /** Take an unclaimed claim-mode task (CAS). */
  @PostMapping("/api/msgcenter/tasks/{id}/claim")
  public Map<String, Object> claim(@PathVariable long id) {
    var info = taskService.claim(id, SecurityUtils.currentUserSub(), displayName());
    return claimInfo(info);
  }

  /** Step back from a claimed task; drafts stay attached unless {@code discardDraft}. */
  @PostMapping("/api/msgcenter/tasks/{id}/release")
  public void release(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
    boolean discardDraft =
        body != null && body.get("discardDraft") != null && body.get("discardDraft").asBoolean();
    taskService.release(id, SecurityUtils.currentUserSub(), displayName(), discardDraft, false);
  }

  /** Back to open (claimer): clears claim + drafts; audit rows survive. */
  @PostMapping("/api/msgcenter/tasks/{id}/reset")
  public void reset(@PathVariable long id) {
    taskService.reset(id, SecurityUtils.currentUserSub(), displayName(), false);
  }

  /** Save the working draft (shape-checked only). */
  @PutMapping("/api/msgcenter/tasks/{id}/draft")
  public Map<String, Object> saveDraft(@PathVariable long id, @RequestBody JsonNode body) {
    String actorSub = SecurityUtils.currentUserSub();
    taskService.saveDraft(id, actorSub, displayName(), body.get("data"), stringOf(body, "note"));
    return Map.of("saved", true);
  }

  /** Discard my draft (idempotent). */
  @DeleteMapping("/api/msgcenter/tasks/{id}/draft")
  public void discardDraft(@PathVariable long id) {
    taskService.discardDraft(id, SecurityUtils.currentUserSub(), displayName());
  }

  /** Adopt the predecessor's draft after a takeover. */
  @PostMapping("/api/msgcenter/tasks/{id}/draft/adopt")
  public Map<String, Object> adoptDraft(@PathVariable long id) {
    Map<String, Object> data =
        taskService.adoptDraft(id, SecurityUtils.currentUserSub(), displayName());
    return Map.of("data", data);
  }

  /** My response for a task (completed-task audit view). */
  @GetMapping("/api/msgcenter/tasks/{id}/my-response")
  public Map<String, Object> myResponse(@PathVariable long id) {
    McTaskResponseEntity response =
        responseRepo.findByMessageIdAndUserSub(id, SecurityUtils.currentUserSub()).orElse(null);
    if (response == null) {
      return Map.of("response", null);
    }
    return Map.of(
        "response",
        Map.of(
            "outcome",
            response.getOutcome(),
            "data",
            response.getDataJson() == null ? Map.of() : response.getDataJson(),
            "note",
            response.getNote() == null ? "" : response.getNote(),
            "respondedAt",
            response.getRespondedAt().toString()));
  }

  private static Map<String, Object> claimInfo(MsgCenterTaskService.ClaimInfo info) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("fromName", info.fromName());
    out.put("savedAt", info.savedAt());
    out.put("draft", info.data() == null ? Map.of() : info.data());
    return out;
  }

  private static String stringOf(JsonNode body, String key) {
    JsonNode node = body == null ? null : body.get(key);
    return node != null && node.isString() ? node.asString() : null;
  }

  private static String displayName() {
    var principal = SecurityUtils.principal();
    return principal != null ? principal.name() : null;
  }
}
