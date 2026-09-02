package com.crosshubber.portal.modules.aihub.conversations;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

/** JPA entity for {@code ai_hub_conversations} table. */
@Entity
@Table(
    name = "ai_hub_conversations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "external_chat_id"}))
public class AiHubConversationEntity implements Persistable<String> {

  @Id
  @Column(name = "id")
  private String id;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "channel_id")
  private String channelId;

  @Column(name = "external_chat_id")
  private String externalChatId;

  @Column(name = "origin", nullable = false)
  private String origin;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
    if (origin == null) {
      origin = "portal";
    }
    if (title == null) {
      title = "New Chat";
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  @Transient private boolean isNew = true;

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getChannelId() {
    return channelId;
  }

  public void setChannelId(String channelId) {
    this.channelId = channelId;
  }

  public String getExternalChatId() {
    return externalChatId;
  }

  public void setExternalChatId(String externalChatId) {
    this.externalChatId = externalChatId;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String origin) {
    this.origin = origin;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
