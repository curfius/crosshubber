package com.crosshubber.portal.modules.aihub.channels;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubChannelInboundEntity}. */
@Repository
public interface AiHubChannelInboundRepository
    extends JpaRepository<AiHubChannelInboundEntity, AiHubChannelInboundId> {

  List<AiHubChannelInboundEntity> findByChannelId(String channelId);

  boolean existsByChannelIdAndExternalMessageId(String channelId, String externalMessageId);
}
