package com.crosshubber.portal.modules.navigation.usersettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link NavigationUserSettingsEntity}. */
@Repository
public interface NavigationUserSettingsRepository
    extends JpaRepository<NavigationUserSettingsEntity, String> {}
