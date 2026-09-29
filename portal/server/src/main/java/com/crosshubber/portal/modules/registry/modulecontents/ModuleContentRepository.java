package com.crosshubber.portal.modules.registry.modulecontents;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link ModuleContentEntity}. */
@Repository
public interface ModuleContentRepository extends JpaRepository<ModuleContentEntity, Long> {

  List<ModuleContentEntity> findByModuleKey(String moduleKey);

  List<ModuleContentEntity> findByCategoryOrderBySortOrderAscNameAsc(
      ModuleContentCategory category);

  List<ModuleContentEntity> findByModuleKeyOrderBySortOrderAscNameAsc(String moduleKey);

  Optional<ModuleContentEntity> findByModuleKeyAndContentKey(String moduleKey, String contentKey);

  List<ModuleContentEntity> findByGroupKey(String groupKey);
}
