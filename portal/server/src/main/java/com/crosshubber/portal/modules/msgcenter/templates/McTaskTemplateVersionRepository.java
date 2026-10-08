package com.crosshubber.portal.modules.msgcenter.templates;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McTaskTemplateVersionRepository
    extends JpaRepository<McTaskTemplateVersionEntity, Long> {

  Optional<McTaskTemplateVersionEntity> findByTemplateIdAndVersion(Long templateId, int version);

  List<McTaskTemplateVersionEntity> findByTemplateIdOrderByVersionDesc(Long templateId);
}
