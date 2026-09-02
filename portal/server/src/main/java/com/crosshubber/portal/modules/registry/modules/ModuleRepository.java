package com.crosshubber.portal.modules.registry.modules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link ModuleEntity}. */
@Repository
public interface ModuleRepository extends JpaRepository<ModuleEntity, String> {}
