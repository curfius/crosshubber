package com.crosshubber.staffing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CandidateProfileRepository extends JpaRepository<CandidateProfileEntity, UUID> {

  List<CandidateProfileEntity> findByStatus(CandidateProfileEntity.ProfileStatus status);

  List<CandidateProfileEntity> findBySourceRef(String sourceRef);
}
