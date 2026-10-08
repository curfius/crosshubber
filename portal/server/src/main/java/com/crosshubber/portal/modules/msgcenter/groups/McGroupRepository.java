package com.crosshubber.portal.modules.msgcenter.groups;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McGroupRepository extends JpaRepository<McGroupEntity, Long> {

  Optional<McGroupEntity> findByKey(String key);
}
