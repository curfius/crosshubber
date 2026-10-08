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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Current draft only (history lives in {@code mc_task_activity}); unique per (message, user).
 * Shape-checked payload — never schema-validated, so partial form data is legal.
 */
@Entity
@Table(
    name = "mc_task_drafts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_sub"}))
public class McTaskDraftEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "message_id", nullable = false, updatable = false)
  private Long messageId;

  @Column(name = "user_sub", nullable = false, updatable = false, length = 128)
  private String userSub;

  @Column(name = "user_name", updatable = false, length = 128)
  private String userName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data_json", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> dataJson;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

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

  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
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

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
