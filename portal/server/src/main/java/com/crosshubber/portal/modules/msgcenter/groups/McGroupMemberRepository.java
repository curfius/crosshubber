package com.crosshubber.portal.modules.msgcenter.groups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McGroupMemberRepository extends JpaRepository<McGroupMemberEntity, Long> {

  List<McGroupMemberEntity> findByGroupId(Long groupId);

  List<McGroupMemberEntity> findByUserSub(String userSub);

  Optional<McGroupMemberEntity> findByGroupIdAndUserSub(Long groupId, String userSub);
}
