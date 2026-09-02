package com.crosshubber.portal.modules.i18n.languages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link I18nLanguageEntity}. */
@Repository
public interface I18nLanguageRepository extends JpaRepository<I18nLanguageEntity, String> {

  List<I18nLanguageEntity> findByEnabledTrueOrderBySortOrderAsc();
}
