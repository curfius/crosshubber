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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * One response row per (task, user) — insert-on-conflict update scoped to the user; the unique
 * constraint plus optimistic versioning give the 409-on-race semantics (plan §5, V26 pattern).
 */
@Entity
@Table(
    name = "mc_task_responses",
    uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_sub"}))
public class McTaskResponseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "message_id", nullable = false, updatable = false)
  private Long messageId;

  @Column(name = "user_sub", nullable = false, updatable = false, length = 128)
  private String userSub;

  /** approve | deny | submit | skip. */
  @Column(nullable = false, length = 16)
  private String outcome;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data_json", columnDefinition = "jsonb")
  private Map<String, Object> dataJson;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "responded_at", nullable = false)
  private Instant respondedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @PrePersist
  void stampRespondedAt() {
    if (respondedAt == null) {
      respondedAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public Long getMessageId() {
    return messageId;
  }

  public void setMessageId(Long messageId) {
    this.messageId = messageId;
  }

  public String getUserSub() {
    return userSub;
  }

  public void setUserSub(String userSub) {
    this.userSub = userSub;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public Map<String, Object> getDataJson() {
    return dataJson;
  }

  public void setDataJson(Map<String, Object> dataJson) {
    this.dataJson = dataJson;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public Instant getRespondedAt() {
    return respondedAt;
  }

  public void setRespondedAt(Instant respondedAt) {
    this.respondedAt = respondedAt;
  }

  public long getVersion() {
    return version;
  }
}
