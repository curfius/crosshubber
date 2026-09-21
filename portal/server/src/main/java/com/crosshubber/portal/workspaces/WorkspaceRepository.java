package com.crosshubber.portal.workspaces;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link WorkspaceEntity} with composite key (userId, name). */
@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, WorkspaceId> {

  List<WorkspaceEntity> findByUserIdOrderBySavedAtDesc(String userId);

  Optional<WorkspaceEntity> findByUserIdAndName(String userId, String name);

  Optional<WorkspaceEntity> findByUserIdAndId(String userId, UUID id);
}
