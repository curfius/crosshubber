package com.crosshubber.solutions.settings;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleSettingRepository extends JpaRepository<ModuleSettingEntity, String> {

  Optional<ModuleSettingEntity> findByKey(String key);
}
