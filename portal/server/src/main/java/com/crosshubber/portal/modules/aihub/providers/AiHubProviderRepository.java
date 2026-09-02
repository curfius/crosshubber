package com.crosshubber.portal.modules.aihub.providers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link AiHubProviderEntity}. */
@Repository
public interface AiHubProviderRepository extends JpaRepository<AiHubProviderEntity, String> {}
