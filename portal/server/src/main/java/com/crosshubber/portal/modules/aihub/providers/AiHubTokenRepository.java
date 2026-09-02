package com.crosshubber.portal.modules.aihub.providers;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubTokenEntity}. */
@Repository
public interface AiHubTokenRepository extends JpaRepository<AiHubTokenEntity, String> {

  List<AiHubTokenEntity> findByProviderId(String providerId);

  List<AiHubTokenEntity> findByProviderIdOrderByNameAsc(String providerId);

  List<AiHubTokenEntity> findByEnabledTrue();
}
