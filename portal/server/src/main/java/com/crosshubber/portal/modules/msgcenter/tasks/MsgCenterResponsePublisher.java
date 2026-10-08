package com.crosshubber.portal.modules.msgcenter.tasks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.crosshubber.portal.common.events.EnvelopeValidationException;
import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseEntity;

/**
 * Response-event publish (plan §7): {@code portal.taskresponse.<moduleKey>.<event>}; called AFTER
 * the response transaction commits. The {@code taskresponse} token (not {@code task.response})
 * keeps response events OUTSIDE the durable msgcenter consumer's filter (portal.msg.> +
 * portal.task.>) so they never land in the inbox/DLQ. With no {@code completionEvent}: approval
 * defaults to {@code approved|denied}, collect to {@code submit}, anything else to the outcome.
 */
@Component
public class MsgCenterResponsePublisher {

  private static final Logger log = LoggerFactory.getLogger(MsgCenterResponsePublisher.class);

  private final EventPublisher eventPublisher;
  private final tools.jackson.databind.ObjectMapper mapper;

  public MsgCenterResponsePublisher(
      EventPublisher eventPublisher, tools.jackson.databind.ObjectMapper mapper) {
    this.eventPublisher = eventPublisher;
    this.mapper = mapper;
  }

  /** Publishes after commit; publish failures are logged (the audit row already exists). */
  public void publishResponse(McMessageEntity message, McTaskResponseEntity response) {
    String event = responseEventOf(message, response);
    String subject =
        com.crosshubber.portal.config.NatsConnectionConfig.SUBJECT_TASK_RESPONSE_PREFIX
            + message.getModuleKey()
            + "."
            + event;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("v", 1);
    payload.put("type", "task-response");
    payload.put("moduleKey", "msgcenter");
    payload.put("id", response.getId() + "-" + response.getVersion());
    payload.put("threadId", message.getThreadId());
    payload.put("eventRef", message.getEventId());
    payload.put("responder", response.getUserSub());
    payload.put("outcome", response.getOutcome());
    if (response.getDataJson() != null) {
      payload.put("data", response.getDataJson());
    }
    if (response.getNote() != null) {
      payload.put("note", response.getNote());
    }
    payload.put(
        "respondedAt",
        response.getRespondedAt() == null
            ? Instant.now().toString()
            : response.getRespondedAt().toString());
    try {
      eventPublisher.publish(subject, serialize(payload));
    } catch (EnvelopeValidationException e) {
      log.error(
          "[msgcenter] response publish failed for task {}: {}", message.getId(), e.getMessage());
    }
  }

  static String responseEventOf(McMessageEntity message, McTaskResponseEntity response) {
    Object declared = message.getTaskJson().get("completionEvent");
    if (declared instanceof String event && !event.isBlank()) {
      return event;
    }
    return switch (response.getOutcome()) {
      case "approve" -> "approved";
      case "deny" -> "denied";
      case "submit" -> "submit";
      default -> response.getOutcome();
    };
  }

  private String serialize(Map<String, Object> payload) {
    return mapper.writeValueAsString(payload);
  }
}
