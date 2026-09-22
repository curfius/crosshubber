package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link NavigationPinnedAppEntity}. */
@Repository
public interface NavigationPinnedAppRepository
    extends JpaRepository<NavigationPinnedAppEntity, UUID> {

  List<NavigationPinnedAppEntity> findByUserIdOrderBySortOrderAscCreatedAtAsc(String userId);

  List<NavigationPinnedAppEntity> findByUserIdAndParentId(String userId, UUID parentId);

  List<NavigationPinnedAppEntity> findByUserIdAndParentIdIsNull(String userId);

  boolean existsByUserIdAndNodeTypeAndRef(String userId, String nodeType, String ref);

  /**
   * Bulk delete for the tree replace path. MUST be a single statement: the per-entity derived
   * delete removes parents first and the {@code parent_id ON DELETE CASCADE} makes the following
   * child DELETEs hit 0 rows (StaleStateException → 500).
   */
  @Modifying
  @Query("DELETE FROM NavigationPinnedAppEntity p WHERE p.userId = :userId")
  void deleteAllForUser(@Param("userId") String userId);

  void deleteByUserIdAndNodeTypeAndRef(String userId, String nodeType, String ref);

  @Query("SELECT max(p.sortOrder) FROM NavigationPinnedAppEntity p WHERE p.userId = :userId")
  Optional<Integer> findMaxSortOrder(@Param("userId") String userId);
}
