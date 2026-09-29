package com.crosshubber.portal.modules.navigation.groups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;

/** Repository for {@link NavigationGroupEntity}. */
@Repository
public interface NavigationGroupRepository extends JpaRepository<NavigationGroupEntity, Long> {

  Optional<NavigationGroupEntity> findByGroupKey(String groupKey);

  List<NavigationGroupEntity> findByCategoryOrderBySortOrderAscNameAsc(
      ModuleContentCategory category);
}
