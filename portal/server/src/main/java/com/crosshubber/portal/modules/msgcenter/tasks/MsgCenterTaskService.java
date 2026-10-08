package com.crosshubber.portal.modules.msgcenter.tasks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskDraftEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskDraftRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseRepository;

import tools.jackson.databind.JsonNode;

/**
 * Task lifecycle CAS machinery (plan §5/§6, amendment decisions 10–13).
 *
 * <p>State machine for claim-mode tasks: {@code open → claimed → done}; plain any-tasks close
 * {@code open → done} on first submit; each-tasks close when the final audience member responds.
 * {@code done} is terminal. Every transition appends an audit row inside the same transaction.
 * Submit data goes through {@link SubmitDataValidator}; draft writes are shape-checked only.
 */
@Service
public class MsgCenterTaskService {

  private final McMessageRepository messageRepo;
  private final McTaskResponseRepository responseRepo;
  private final McTaskDraftRepository draftRepo;
  private final McTaskActivityRepository activityRepo;
  private final AllowlistSubmitValidator dataShape;

  public MsgCenterTaskService(
      McMessageRepository messageRepo,
      McTaskResponseRepository responseRepo,
      McTaskDraftRepository draftRepo,
      McTaskActivityRepository activityRepo,
      AllowlistSubmitValidator dataShape) {
    this.messageRepo = messageRepo;
    this.responseRepo = responseRepo;
    this.draftRepo = draftRepo;
    this.activityRepo = activityRepo;
    this.dataShape = dataShape;
  }

  /** What a new claimer is offered after a release (draft handoff context). */
  public record ClaimInfo(String fromName, String savedAt, Map<String, Object> data) {}

  /** Outcome of a submit — closed=true when the submit flipped the task to done. */
  public record SubmitResult(
      boolean closed, McMessageEntity message, McTaskResponseEntity response) {}

  /** One of the audience members takes an unclaimed claim-mode task (CAS, 409 losers). */
  @Transactional
  public ClaimInfo claim(long messageId, String actorSub, String actorName) {
    McMessageEntity message = taskMessage(messageId);
    if (!claimMode(message)) {
      throw badRequest("task is not claimable");
    }
    if ("done".equals(message.getStatus())) {
      throw conflict("task already completed");
    }
    if ("claimed".equals(message.getStatus())) {
      throw conflict("task already claimed");
    }
    if (expired(message)) {
      throw conflict("task expired");
    }
    message.setStatus("claimed");
    message.setClaimedBySub(actorSub);
    message.setClaimedByName(actorName);
    message.setClaimedAt(Instant.now());
    appendActivity(message, actorSub, actorName, "claim", null);
    return previousDraftOf(message, actorSub);
  }

  /** The claimer steps back; drafts stay attached (takeover context) unless discardDraft. */
  @Transactional
  public void release(
      long messageId,
      String actorSub,
      String actorName,
      boolean discardDraft,
      boolean adminOverride) {
    McMessageEntity message = taskMessage(messageId);
    if (!"claimed".equals(message.getStatus())) {
      throw conflict("task is not claimed");
    }
    requireClaimer(message, actorSub, adminOverride);
    if (discardDraft) {
      discardAllDrafts(message, actorSub, actorName);
    }
    clearClaim(message);
    appendActivity(
        message,
        actorSub,
        actorName,
        adminOverride ? "admin_force_release" : "release",
        Map.of("keptDraft", !discardDraft));
  }

  /**
   * Back to open: clears claim + drafts. Claim-mode: claimer or admin. Plain tasks: admin-only
   * (users discard their personal draft instead). Activity rows survive — audit never deleted.
   */
  @Transactional
  public void reset(long messageId, String actorSub, String actorName, boolean adminOverride) {
    McMessageEntity message = taskMessage(messageId);
    if ("done".equals(message.getStatus())) {
      throw conflict("done tasks are terminal");
    }
    boolean claimMode = claimMode(message);
    if (!claimMode && !adminOverride) {
      throw badRequest("task is not claimable — discard your own draft instead");
    }
    if (claimMode) {
      requireClaimer(message, actorSub, adminOverride);
    }
    discardAllDrafts(message, actorSub, actorName);
    boolean wasClaimed = "claimed".equals(message.getStatus());
    clearClaim(message);
    appendActivity(
        message,
        actorSub,
        actorName,
        adminOverride ? "admin_reset" : "reset",
        wasClaimed ? Map.of("releasedClaim", true) : null);
  }

  /** Saves the working draft (shape-checked only — partial data is the point). */
  @Transactional
  public void saveDraft(
      long messageId, String actorSub, String actorName, JsonNode data, String note) {
    McMessageEntity message = taskMessage(messageId);
    requireTask(message);
    Map<String, Object> dataMap = dataShape.shapeCheck(message, data);
    McTaskDraftEntity draft =
        draftRepo
            .findByMessageIdAndUserSub(messageId, actorSub)
            .orElseGet(
                () -> {
                  McTaskDraftEntity created = new McTaskDraftEntity();
                  created.setMessageId(messageId);
                  created.setUserSub(actorSub);
                  created.setUserName(actorName);
                  return created;
                });
    draft.setDataJson(dataMap);
    draft.setNote(note);
    draft.setUpdatedAt(Instant.now());
    draftRepo.save(draft);
    appendActivity(message, actorSub, actorName, "draft_save", dataMap);
  }

  /** Discards the caller's own draft row (audit row remains); idempotent. */
  @Transactional
  public void discardDraft(long messageId, String actorSub, String actorName) {
    McTaskDraftEntity draft = draftRepo.findByMessageIdAndUserSub(messageId, actorSub).orElse(null);
    if (draft == null) {
      return;
    }
    appendActivity(message(messageId), actorSub, actorName, "draft_discard", draft.getDataJson());
    draftRepo.delete(draft);
  }

  /** Claimer adopts the predecessor's draft (takeover where the task was left at). */
  @Transactional
  public Map<String, Object> adoptDraft(long messageId, String actorSub, String actorName) {
    McMessageEntity message = taskMessage(messageId);
    requireClaimer(message, actorSub, false);
    McTaskDraftEntity predecessor =
        draftRepo.findByMessageId(messageId).stream()
            .filter(d -> !actorSub.equals(d.getUserSub()))
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no draft to adopt"));
    McTaskDraftEntity mine =
        draftRepo
            .findByMessageIdAndUserSub(messageId, actorSub)
            .orElseGet(
                () -> {
                  McTaskDraftEntity created = new McTaskDraftEntity();
                  created.setMessageId(messageId);
                  created.setUserSub(actorSub);
                  created.setUserName(actorName);
                  return created;
                });
    mine.setDataJson(predecessor.getDataJson());
    mine.setNote(predecessor.getNote());
    mine.setUpdatedAt(Instant.now());
    draftRepo.save(mine);
    appendActivity(message, actorSub, actorName, "adopt", predecessor.getDataJson());
    return predecessor.getDataJson();
  }

  /** Offer context for a fresh claim: the released draft, if any. */
  public ClaimInfo previousDraftOf(McMessageEntity message, String actorSub) {
    return draftRepo.findByMessageId(message.getId()).stream()
        .filter(d -> !actorSub.equals(d.getUserSub()))
        .findFirst()
        .map(
            d ->
                new ClaimInfo(
                    d.getUserName(),
                    d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString(),
                    d.getDataJson()))
        .orElse(new ClaimInfo(null, null, null));
  }

  /**
   * Submit path — upserts the response row, closes the task per completion mode; the caller
   * publishes the response event AFTER this transaction commits.
   */
  @Transactional
  public SubmitResult respond(
      long messageId,
      String actorSub,
      String actorName,
      String outcome,
      JsonNode data,
      String note,
      SubmitDataValidator dataValidator) {
    McMessageEntity message = taskMessage(messageId);
    if ("done".equals(message.getStatus())) {
      throw conflict("task already completed");
    }
    if (expired(message)) {
      throw conflict("task expired");
    }
    if (claimMode(message)) {
      requireClaimer(message, actorSub, false);
    }
    Map<String, Object> validatedData =
        data != null && !data.isNull()
            ? dataValidator.validate(message, data, actorSub, actorName)
            : null;

    McTaskResponseEntity response =
        responseRepo
            .findByMessageIdAndUserSub(messageId, actorSub)
            .orElseGet(
                () -> {
                  McTaskResponseEntity created = new McTaskResponseEntity();
                  created.setMessageId(messageId);
                  created.setUserSub(actorSub);
                  return created;
                });
    response.setOutcome(outcome);
    if (validatedData != null) {
      response.setDataJson(validatedData);
    }
    response.setNote(note);
    responseRepo.save(response);

    boolean closed = closeTaskPerCompletion(message);
    appendActivity(
        message, actorSub, actorName, "respond", responseDetail(outcome, validatedData, note));
    return new SubmitResult(closed, message, response);
  }

  private boolean closeTaskPerCompletion(McMessageEntity message) {
    String completion = completionOf(message);
    if ("each".equals(completion)) {
      long expected = audienceUsers(message).size();
      long actual = responseRepo.countByMessageId(message.getId());
      if (actual >= expected) {
        message.setStatus("done");
        return true;
      }
      return false;
    }
    if (claimMode(message)) {
      if (!"claimed".equals(message.getStatus())) {
        throw conflict("task was reset — claim it again");
      }
    } else if (!"open".equals(message.getStatus())) {
      throw conflict("task was claimed by another user");
    }
    message.setStatus("done");
    return true;
  }

  private void requireClaimer(McMessageEntity message, String actorSub, boolean adminOverride) {
    if (adminOverride) {
      return; // caller pre-checked the admin role
    }
    if (actorSub != null && actorSub.equals(message.getClaimedBySub())) {
      return;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "task is claimed by another user");
  }

  private void clearClaim(McMessageEntity message) {
    message.setClaimedBySub(null);
    message.setClaimedByName(null);
    message.setClaimedAt(null);
    message.setStatus("open");
  }

  private void discardAllDrafts(McMessageEntity message, String actorSub, String actorName) {
    List<McTaskDraftEntity> drafts = draftRepo.findByMessageId(message.getId());
    for (McTaskDraftEntity draft : drafts) {
      appendActivity(
          message, draft.getUserSub(), draft.getUserName(), "draft_discard", draft.getDataJson());
      draftRepo.delete(draft);
    }
  }

  private void appendActivity(
      McMessageEntity message,
      String actorSub,
      String actorName,
      String action,
      Map<String, Object> detail) {
    McTaskActivityEntity row = new McTaskActivityEntity();
    row.setMessageId(message.getId());
    row.setActorSub(actorSub);
    row.setActorName(actorName);
    row.setAction(action);
    row.setDetailJson(detail);
    activityRepo.save(row);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseDetail(
      String outcome, Map<String, Object> data, String note) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("outcome", outcome);
    if (data != null) {
      detail.put("data", data);
    }
    if (note != null) {
      detail.put("note", note);
    }
    return detail;
  }

  private McMessageEntity taskMessage(long messageId) {
    McMessageEntity message = messageRepo.findById(messageId).orElse(null);
    if (message == null || message.getTaskJson() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + messageId);
    }
    return message;
  }

  private McMessageEntity message(long messageId) {
    return messageRepo
        .findById(messageId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found"));
  }

  private void requireTask(McMessageEntity message) {
    if (!"task".equals(message.getMsgType())) {
      throw badRequest("message is not a task");
    }
  }

  static boolean claimMode(McMessageEntity message) {
    Object claim = message.getTaskJson().get("claim");
    return claim instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("enabled"));
  }

  static String completionOf(McMessageEntity message) {
    Object completion = message.getTaskJson().get("completion");
    return completion instanceof String s ? s : "any";
  }

  static List<String> audienceUsers(McMessageEntity message) {
    Object users = message.getAudienceJson().get("users");
    if (!(users instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .distinct()
        .toList();
  }

  static boolean expired(McMessageEntity message) {
    Object expiresAt = message.getTaskJson().get("expiresAt");
    if (!(expiresAt instanceof String raw)) {
      return false;
    }
    try {
      return Instant.parse(raw).isBefore(Instant.now());
    } catch (Exception e) {
      return false;
    }
  }

  private static ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
