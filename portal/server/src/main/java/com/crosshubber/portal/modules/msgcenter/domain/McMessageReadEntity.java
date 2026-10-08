package com.crosshubber.portal.modules.msgcenter.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Per-user read marker (insert-or-ignore, unique per message+user); never updated. */
@Entity
@Table(
    name = "mc_message_reads",
    uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_sub"}))
public class McMessageReadEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "message_id", nullable = false, updatable = false)
  private Long messageId;

  @Column(name = "user_sub", nullable = false, updatable = false, length = 128)
  private String userSub;

  @Column(name = "read_at", nullable = false, updatable = false)
  private Instant readAt;

  protected McMessageReadEntity() {}

  public McMessageReadEntity(Long messageId, String userSub) {
    this.messageId = messageId;
    this.userSub = userSub;
    this.readAt = Instant.now();
  }

  @PrePersist
  void stampReadAt() {
    if (readAt == null) {
      readAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public Long getMessageId() {
    return messageId;
  }

  public String getUserSub() {
    return userSub;
  }

  public Instant getReadAt() {
    return readAt;
  }
}
