package com.crosshubber.portal.modules.settings.instance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link InstanceSettingsEntity} (singleton id=1). */
@Repository
public interface InstanceSettingsRepository
    extends JpaRepository<InstanceSettingsEntity, Integer> {}
