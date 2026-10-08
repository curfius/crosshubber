package com.crosshubber.portal.modules.aihub.settings;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the single-row {@code ai_hub_settings} table. */
public interface AiHubSettingsRepository extends JpaRepository<AiHubSettingsEntity, String> {}
