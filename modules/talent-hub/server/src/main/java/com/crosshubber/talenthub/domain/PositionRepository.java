package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, UUID> {

  List<PositionEntity> findByRfpIdOrderByOrderIndexAsc(UUID rfpId);
}
