package com.crosshubber.portal.modules.msgcenter.domain;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One durable row per consumed portal.msg/portal.task event (plan §5). */
@Entity
@Table(name = "mc_messages")
public class McMessageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true, updatable = false)
  private String eventId;

  @Column(name = "msg_type", nullable = false, updatable = false, length = 32)
  private String msgType;

  @Column(name = "module_key", nullable = false, updatable = false, length = 64)
  private String moduleKey;

  @Column(name = "nats_subject", nullable = false, updatable = false, length = 256)
  private String natsSubject;

  @Column(name = "nats_seq", updatable = false)
  private Long natsSeq;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "audience_json", nullable = false, columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> audienceJson;

  @Column(name = "sender_name", length = 100, updatable = false)
  private String senderName;

  @Column(name = "sender_color", length = 32, updatable = false)
  private String senderColor;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "title_json", nullable = false, columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> titleJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "body_json", nullable = false, columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> bodyJson;

  @Column(name = "severity", length = 16, updatable = false)
  private String severity;

  @Column(name = "thread_id", length = 128, updatable = false)
  private String threadId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "link_json", columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> linkJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "task_json", columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> taskJson;

  /** Task lifecycle: open | claimed | done. Immutable for notification/message rows. */
  @Column(name = "status", nullable = false, length = 16)
  private String status = "open";

  @Column(name = "claimed_by_sub", length = 128)
  private String claimedBySub;

  @Column(name = "claimed_by_name", length = 128)
  private String claimedByName;

  @Column(name = "claimed_at")
  private Instant claimedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @PrePersist
  void stampDefaults() {
    if (status == null) {
      status = "open";
    }
  }

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public String getMsgType() {
    return msgType;
  }

  public void setMsgType(String msgType) {
    this.msgType = msgType;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public void setModuleKey(String moduleKey) {
    this.moduleKey = moduleKey;
  }

  public String getNatsSubject() {
    return natsSubject;
  }

  public void setNatsSubject(String natsSubject) {
    this.natsSubject = natsSubject;
  }

  public Long getNatsSeq() {
    return natsSeq;
  }

  public void setNatsSeq(Long natsSeq) {
    this.natsSeq = natsSeq;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
  }

  public Map<String, Object> getAudienceJson() {
    return audienceJson;
  }

  public void setAudienceJson(Map<String, Object> audienceJson) {
    this.audienceJson = audienceJson;
  }

  public String getSenderName() {
    return senderName;
  }

  public void setSenderName(String senderName) {
    this.senderName = senderName;
  }

  public String getSenderColor() {
    return senderColor;
  }

  public void setSenderColor(String senderColor) {
    this.senderColor = senderColor;
  }

  public Map<String, Object> getTitleJson() {
    return titleJson;
  }

  public void setTitleJson(Map<String, Object> titleJson) {
    this.titleJson = titleJson;
  }

  public Map<String, Object> getBodyJson() {
    return bodyJson;
  }

  public void setBodyJson(Map<String, Object> bodyJson) {
    this.bodyJson = bodyJson;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getThreadId() {
    return threadId;
  }

  public void setThreadId(String threadId) {
    this.threadId = threadId;
  }

  public Map<String, Object> getLinkJson() {
    return linkJson;
  }

  public void setLinkJson(Map<String, Object> linkJson) {
    this.linkJson = linkJson;
  }

  public Map<String, Object> getTaskJson() {
    return taskJson;
  }

  public void setTaskJson(Map<String, Object> taskJson) {
    this.taskJson = taskJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getClaimedBySub() {
    return claimedBySub;
  }

  public void setClaimedBySub(String claimedBySub) {
    this.claimedBySub = claimedBySub;
  }

  public String getClaimedByName() {
    return claimedByName;
  }

  public void setClaimedByName(String claimedByName) {
    this.claimedByName = claimedByName;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(Instant claimedAt) {
    this.claimedAt = claimedAt;
  }

  public long getVersion() {
    return version;
  }
}
