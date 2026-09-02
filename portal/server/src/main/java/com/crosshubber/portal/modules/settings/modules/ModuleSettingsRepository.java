package com.crosshubber.portal.modules.settings.modules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link ModuleSettingsEntity}. */
@Repository
public interface ModuleSettingsRepository extends JpaRepository<ModuleSettingsEntity, String> {}
