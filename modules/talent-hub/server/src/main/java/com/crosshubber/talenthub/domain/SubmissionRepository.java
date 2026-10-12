package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubmissionRepository extends JpaRepository<SubmissionEntity, UUID> {

  List<SubmissionEntity> findByRfpIdOrderBySentAtDesc(UUID rfpId);
}
