package com.crosshubber.portal.modules.i18n.labels;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link I18nLabelEntity}. */
@Repository
public interface I18nLabelRepository extends JpaRepository<I18nLabelEntity, I18nLabelId> {

  List<I18nLabelEntity> findByLanguageCode(String languageCode);

  List<I18nLabelEntity> findByKey(String key);
}
