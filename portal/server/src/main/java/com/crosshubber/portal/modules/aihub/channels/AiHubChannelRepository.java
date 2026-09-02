package com.crosshubber.portal.modules.aihub.channels;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubChannelEntity}. */
@Repository
public interface AiHubChannelRepository extends JpaRepository<AiHubChannelEntity, String> {

  List<AiHubChannelEntity> findByType(String type);

  List<AiHubChannelEntity> findByEnabledTrue();

  List<AiHubChannelEntity> findAllByOrderByCreatedAtAsc();

  List<AiHubChannelEntity> findByEnabledTrueAndTypeAndDeliveryMode(
      String type, String deliveryMode);
}
