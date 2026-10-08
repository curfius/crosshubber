package com.crosshubber.portal.modules.msgcenter.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageRepository;

import tools.jackson.databind.JsonNode;

/**
 * Transactional insert of a validated envelope into {@code mc_messages}.
 *
 * <p>Insert-only: a duplicate {@code event_id} makes the whole store a no-op (at-least-once
 * dedupe). Callers must never run NATS I/O in the same thread as this TX (ack happens after the
 * transactional method returns — commit boundary).
 */
@Service
public class MsgCenterIngestionService {

  private final McMessageRepository messageRepo;
  private final JsonUtils jsonUtils;

  public MsgCenterIngestionService(McMessageRepository messageRepo, JsonUtils jsonUtils) {
    this.messageRepo = messageRepo;
    this.jsonUtils = jsonUtils;
  }

  /**
   * Stores the validated envelope; returns the stored row, or null when the event was already
   * ingested (dedupe no-op). The caller (ingest handler) fires the email mirror AFTER commit — the
   * method's return boundary is the commit point.
   */
  @Transactional
  public McMessageEntity store(JsonNode envelope, String natsSubject, long natsSeq) {
    String eventId = stringOrNull(envelope.get("id"));
    if (eventId == null) {
      return null; // unreachable after validation; defensive no-op
    }
    if (messageRepo.findByEventId(eventId).isPresent()) {
      return null;
    }

    McMessageEntity entity = new McMessageEntity();
    entity.setEventId(eventId);
    entity.setMsgType(stringOrNull(envelope.get("type")));
    entity.setModuleKey(stringOrNull(envelope.get("moduleKey")));
    entity.setNatsSubject(natsSubject);
    entity.setNatsSeq(natsSeq);
    Instant createdAt = parseInstant(envelope.get("createdAt"));
    entity.setOccurredAt(createdAt != null ? createdAt : Instant.now());
    entity.setAudienceJson(jsonUtils.toMap(envelope.get("audience")));

    JsonNode sender = envelope.get("sender");
    if (sender != null && sender.isObject()) {
      entity.setSenderName(stringOrNull(sender.get("name")));
      entity.setSenderColor(stringOrNull(sender.get("color")));
    }

    entity.setTitleJson(jsonUtils.toMap(envelope.get("title")));
    entity.setBodyJson(jsonUtils.toMap(envelope.get("body")));
    entity.setSeverity(stringOrNull(envelope.get("severity")));
    entity.setThreadId(stringOrNull(envelope.get("threadId")));
    entity.setLinkJson(jsonUtils.toMap(envelope.get("link")));

    JsonNode task = envelope.get("task");
    if (task != null && task.isObject()) {
      entity.setTaskJson(jsonUtils.toMap(task));
      entity.setStatus("open");
    }
    messageRepo.save(entity);
    return entity;
  }

  /** Audience keys of a stored message (for tests and admin tooling). */
  public List<String> audienceKeys(JsonNode audience) {
    Map<String, Object> map = jsonUtils.toMap(audience);
    return map == null ? List.of() : List.copyOf(map.keySet());
  }

  private static String stringOrNull(JsonNode node) {
    return node != null && node.isString() ? node.asString() : null;
  }

  private static Instant parseInstant(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    try {
      return Instant.parse(node.asString());
    } catch (Exception e) {
      return null;
    }
  }
}
