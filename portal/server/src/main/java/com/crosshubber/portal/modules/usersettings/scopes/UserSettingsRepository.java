package com.crosshubber.portal.modules.usersettings.scopes;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link UserSettingsEntity}. */
@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettingsEntity, UserSettingsId> {

  List<UserSettingsEntity> findByUserId(String userId);

  Optional<UserSettingsEntity> findByUserIdAndScope(String userId, String scope);
}
