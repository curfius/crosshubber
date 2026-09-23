package com.crosshubber.portal.modules.registry.manifest;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link ModuleVersionEntity}. */
@Repository
public interface ModuleVersionRepository extends JpaRepository<ModuleVersionEntity, Long> {

  List<ModuleVersionEntity> findByModuleKeyOrderByInstalledAtDesc(String moduleKey);

  Optional<ModuleVersionEntity> findByModuleKeyAndId(String moduleKey, Long id);

  Optional<ModuleVersionEntity> findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(
      String moduleKey, VersionStatus status);

  List<ModuleVersionEntity> findByModuleKeyAndStatus(String moduleKey, VersionStatus status);

  boolean existsByModuleKeyAndStatus(String moduleKey, VersionStatus status);

  long countByModuleKeyAndStatusInAndVersionEndingWith(
      String moduleKey, List<VersionStatus> statuses, String suffix);
}
