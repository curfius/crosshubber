package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link NavigationPinnedAppEntity}. */
@Repository
public interface NavigationPinnedAppRepository
    extends JpaRepository<NavigationPinnedAppEntity, UUID> {

  List<NavigationPinnedAppEntity> findByUserIdOrderBySortOrderAsc(String userId);

  List<NavigationPinnedAppEntity> findByUserIdOrderBySortOrderAscCreatedAtAsc(String userId);

  List<NavigationPinnedAppEntity> findByUserIdAndParentId(String userId, UUID parentId);

  List<NavigationPinnedAppEntity> findByUserIdAndParentIdIsNull(String userId);

  boolean existsByUserIdAndNodeTypeAndRef(String userId, String nodeType, String ref);

  void deleteByUserId(String userId);

  void deleteByUserIdAndNodeTypeAndRef(String userId, String nodeType, String ref);

  @Query("SELECT max(p.sortOrder) FROM NavigationPinnedAppEntity p WHERE p.userId = :userId")
  Optional<Integer> findMaxSortOrder(@Param("userId") String userId);
}
