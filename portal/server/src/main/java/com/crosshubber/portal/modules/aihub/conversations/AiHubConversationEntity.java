package com.crosshubber.portal.modules.aihub.conversations;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.crosshubber.portal.modules.aihub.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_hub_conversations")
@EntityListeners(AuditingEntityListener.class)
public class AiHubConversationEntity extends BaseEntity {

  @Id
  @Column(name = "id")
  private String id;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "title", nullable = false)
  private String title;

  @Convert(converter = ConversationOrigin.DbConverter.class)
  @Column(name = "origin", nullable = false)
  private ConversationOrigin origin;

  @Column(name = "pinned", nullable = false)
  private boolean pinned;

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

  public ConversationOrigin getOrigin() {
    return origin;
  }

  public void setOrigin(ConversationOrigin origin) {
    this.origin = origin;
  }

  public boolean isPinned() {
    return pinned;
  }

  public void setPinned(boolean pinned) {
    this.pinned = pinned;
  }
}
