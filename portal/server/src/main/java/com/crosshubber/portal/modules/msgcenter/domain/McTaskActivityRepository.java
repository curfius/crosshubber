package com.crosshubber.portal.modules.msgcenter.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McTaskActivityRepository extends JpaRepository<McTaskActivityEntity, Long> {

  List<McTaskActivityEntity> findByMessageIdOrderByCreatedAtDescIdDesc(Long messageId);
}
