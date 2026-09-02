package com.crosshubber.portal.modules.aihub.channels;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** JPA entity for {@code ai_hub_channel_inbound} table. */
@Entity
@Table(name = "ai_hub_channel_inbound")
@IdClass(AiHubChannelInboundId.class)
public class AiHubChannelInboundEntity implements Persistable<AiHubChannelInboundId> {

  @Id
  @Column(name = "channel_id", nullable = false)
  private String channelId;

  @Id
  @Column(name = "external_message_id", nullable = false)
  private String externalMessageId;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @PrePersist
  void prePersist() {
    if (receivedAt == null) {
      receivedAt = Instant.now();
    }
  }

  @Transient
  private boolean isNew = true;

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  @Override
  public AiHubChannelInboundId getId() {
    return new AiHubChannelInboundId(channelId, externalMessageId);
  }

  public String getChannelId() {
    return channelId;
  }

  public void setChannelId(String channelId) {
    this.channelId = channelId;
  }

  public String getExternalMessageId() {
    return externalMessageId;
  }

  public void setExternalMessageId(String externalMessageId) {
    this.externalMessageId = externalMessageId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(Instant receivedAt) {
    this.receivedAt = receivedAt;
  }
}
