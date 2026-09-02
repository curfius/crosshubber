package com.crosshubber.portal.modules.aihub.channels;

import java.io.Serializable;
import java.util.Objects;

/** Composite PK for {@link AiHubChannelInboundEntity}. */
public class AiHubChannelInboundId implements Serializable {

  private String channelId;
  private String externalMessageId;

  public AiHubChannelInboundId() {}

  public AiHubChannelInboundId(String channelId, String externalMessageId) {
    this.channelId = channelId;
    this.externalMessageId = externalMessageId;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AiHubChannelInboundId that = (AiHubChannelInboundId) o;
    return Objects.equals(channelId, that.channelId)
        && Objects.equals(externalMessageId, that.externalMessageId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(channelId, externalMessageId);
  }
}
