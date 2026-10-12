package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RfpRepository extends JpaRepository<RfpEntity, UUID> {

  List<RfpEntity> findByPhase(RfpPhase phase);

  List<RfpEntity> findByPhaseOrderByCreatedAtDesc(RfpPhase phase);

  List<RfpEntity> findByClientIdOrderByCreatedAtDesc(UUID clientId);
}
