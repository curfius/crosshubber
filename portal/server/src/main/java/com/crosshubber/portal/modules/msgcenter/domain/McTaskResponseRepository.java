package com.crosshubber.portal.modules.msgcenter.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McTaskResponseRepository extends JpaRepository<McTaskResponseEntity, Long> {

  Optional<McTaskResponseEntity> findByMessageIdAndUserSub(Long messageId, String userSub);

  List<McTaskResponseEntity> findByMessageId(Long messageId);

  long countByMessageId(Long messageId);

  long countByMessageIdAndUserSub(Long messageId, String userSub);
}
