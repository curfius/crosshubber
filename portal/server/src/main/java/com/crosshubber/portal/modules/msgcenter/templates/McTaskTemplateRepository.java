package com.crosshubber.portal.modules.msgcenter.templates;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McTaskTemplateRepository extends JpaRepository<McTaskTemplateEntity, Long> {

  Optional<McTaskTemplateEntity> findByKey(String key);
}
