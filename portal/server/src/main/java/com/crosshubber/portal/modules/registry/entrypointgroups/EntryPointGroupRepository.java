package com.crosshubber.portal.modules.registry.entrypointgroups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crosshubber.portal.modules.registry.entrypoints.EntryPointCategory;

/** Repository for {@link EntryPointGroupEntity}. */
@Repository
public interface EntryPointGroupRepository extends JpaRepository<EntryPointGroupEntity, Long> {

  Optional<EntryPointGroupEntity> findByGroupKey(String groupKey);

  List<EntryPointGroupEntity> findByCategoryOrderBySortOrderAscNameAsc(EntryPointCategory category);
}
