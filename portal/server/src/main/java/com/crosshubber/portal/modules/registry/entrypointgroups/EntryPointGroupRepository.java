package com.crosshubber.portal.modules.registry.entrypointgroups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link EntryPointGroupEntity}. */
@Repository
public interface EntryPointGroupRepository extends JpaRepository<EntryPointGroupEntity, Long> {

  Optional<EntryPointGroupEntity> findByGroupKey(String groupKey);

  List<EntryPointGroupEntity> findByCategoryOrderBySortOrderAscNameAsc(String category);

  List<EntryPointGroupEntity> findByCategory(String category);

  List<EntryPointGroupEntity> findByParentKey(String parentKey);
}
