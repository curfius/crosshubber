package com.crosshubber.portal.modules.msgcenter.groups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McGroupOwnerRepository extends JpaRepository<McGroupOwnerEntity, Long> {

  List<McGroupOwnerEntity> findByGroupId(Long groupId);

  Optional<McGroupOwnerEntity> findByGroupIdAndUserSub(Long groupId, String userSub);
}
