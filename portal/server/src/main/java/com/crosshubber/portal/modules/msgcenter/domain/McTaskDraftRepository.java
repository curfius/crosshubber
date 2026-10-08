package com.crosshubber.portal.modules.msgcenter.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McTaskDraftRepository extends JpaRepository<McTaskDraftEntity, Long> {

  Optional<McTaskDraftEntity> findByMessageIdAndUserSub(Long messageId, String userSub);

  List<McTaskDraftEntity> findByMessageId(Long messageId);
}
