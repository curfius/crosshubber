package com.crosshubber.portal.modules.registry.entrypoints;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link EntryPointEntity}. */
@Repository
public interface EntryPointRepository extends JpaRepository<EntryPointEntity, Long> {

  List<EntryPointEntity> findByModuleKey(String moduleKey);

  List<EntryPointEntity> findByCategory(String category);

  List<EntryPointEntity> findByCategoryOrderBySortOrderAscNameAsc(String category);

  List<EntryPointEntity> findByModuleKeyOrderBySortOrderAscNameAsc(String moduleKey);

  List<EntryPointEntity> findByModuleKeyAndCategory(String moduleKey, String category);

  Optional<EntryPointEntity> findByModuleKeyAndEntryKey(String moduleKey, String entryKey);

  List<EntryPointEntity> findByGroupKey(String groupKey);

  List<EntryPointEntity> findByActiveTrue();

  default List<EntryPointEntity> findByCategory(String category, boolean activeOnly) {
    List<EntryPointEntity> rows = findByCategoryOrderBySortOrderAscNameAsc(category);
    return activeOnly ? rows.stream().filter(EntryPointEntity::getActive).toList() : rows;
  }
}
