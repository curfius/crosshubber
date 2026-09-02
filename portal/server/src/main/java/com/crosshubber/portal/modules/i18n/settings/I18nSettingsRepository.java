package com.crosshubber.portal.modules.i18n.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link I18nSettingsEntity} (singleton id=1). */
@Repository
public interface I18nSettingsRepository extends JpaRepository<I18nSettingsEntity, Integer> {}
