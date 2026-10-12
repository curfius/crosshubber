package com.crosshubber.talenthub.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CandidateProcessRepository extends JpaRepository<CandidateProcessEntity, UUID> {

  List<CandidateProcessEntity> findByPositionIdOrderByCreatedAtAsc(UUID positionId);

  List<CandidateProcessEntity> findByRfpIdOrderByCreatedAtAsc(UUID rfpId);

  List<CandidateProcessEntity> findByCandidateIdOrderByCreatedAtDesc(UUID candidateId);

  Optional<CandidateProcessEntity> findFirstByRfpIdOrderByCreatedAtAsc(UUID rfpId);

  /** Active-process dup guard (D-plan §3.2): re-engagement allowed only when none is active. */
  boolean existsByPositionIdAndCandidateIdAndStageIn(
      UUID positionId, UUID candidateId, Collection<ProcessStage> stages);
}
