package com.crosshubber.portal.modules.aihub.conversations;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubConversationEntity}. */
@Repository
public interface AiHubConversationRepository
    extends JpaRepository<AiHubConversationEntity, String> {

  List<AiHubConversationEntity> findByUserId(String userId);

  List<AiHubConversationEntity> findByUserIdAndOriginOrderByUpdatedAtDesc(
      String userId, String origin);

  Optional<AiHubConversationEntity> findByIdAndUserId(String id, String userId);

  Optional<AiHubConversationEntity> findByChannelIdAndExternalChatId(
      String channelId, String externalChatId);

  List<AiHubConversationEntity> findByChannelId(String channelId);
}
