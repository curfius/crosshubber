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

/**
 * Append-only audit timeline per task (plan §5). Never updated or deleted — even a task reset keeps
 * its history: only the current draft claim/status rows are cleared.
 */
@Entity
@Table(name = "mc_task_activity")
public class McTaskActivityEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "message_id", nullable = false, updatable = false)
  private Long messageId;

  @Column(name = "actor_sub", nullable = false, updatable = false, length = 128)
  private String actorSub;

  @Column(name = "actor_name", updatable = false, length = 128)
  private String actorName;

  /**
   * claim | release | adopt | draft_save | draft_discard | reset | respond | admin_force_release |
   * admin_reset
   */
  @Column(nullable = false, updatable = false, length = 32)
  private String action;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detail_json", columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> detailJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void stampCreatedAt() {
    if (createdAt == null) {
      createdAt = Instant.now();
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

  public String getActorSub() {
    return actorSub;
  }

  public void setActorSub(String actorSub) {
    this.actorSub = actorSub;
  }

  public String getActorName() {
    return actorName;
  }

  public void setActorName(String actorName) {
    this.actorName = actorName;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public Map<String, Object> getDetailJson() {
    return detailJson;
  }

  public void setDetailJson(Map<String, Object> detailJson) {
    this.detailJson = detailJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
