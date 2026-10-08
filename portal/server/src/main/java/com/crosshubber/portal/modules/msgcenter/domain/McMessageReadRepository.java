package com.crosshubber.portal.modules.msgcenter.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McMessageReadRepository extends JpaRepository<McMessageReadEntity, Long> {

  List<McMessageReadEntity> findByUserSub(String userSub);

  boolean existsByMessageIdAndUserSub(Long messageId, String userSub);
}
