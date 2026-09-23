package com.crosshubber.portal.modules.registry.entrypoints;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link EntryPointEntity}. */
@Repository
public interface EntryPointRepository extends JpaRepository<EntryPointEntity, Long> {

  List<EntryPointEntity> findByModuleKey(String moduleKey);

  List<EntryPointEntity> findByCategoryOrderBySortOrderAscNameAsc(EntryPointCategory category);

  List<EntryPointEntity> findByModuleKeyOrderBySortOrderAscNameAsc(String moduleKey);

  Optional<EntryPointEntity> findByModuleKeyAndEntryKey(String moduleKey, String entryKey);

  List<EntryPointEntity> findByGroupKey(String groupKey);
}
