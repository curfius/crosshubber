package com.crosshubber.portal.modules.aihub.conversations;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubMessageEntity}. */
@Repository
public interface AiHubMessageRepository extends JpaRepository<AiHubMessageEntity, Long> {

  List<AiHubMessageEntity> findByConversationIdOrderByIdAsc(String conversationId);

  List<AiHubMessageEntity> findByConversationIdOrderByIdDesc(
      String conversationId, org.springframework.data.domain.Pageable pageable);

  List<AiHubMessageEntity> findByConversationId(String conversationId);

  void deleteByConversationId(String conversationId);
}
