package com.crosshubber.portal.modules.navigation.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link NavigationSettingsEntity} (singleton id=1). */
@Repository
public interface NavigationSettingsRepository
    extends JpaRepository<NavigationSettingsEntity, Integer> {}
