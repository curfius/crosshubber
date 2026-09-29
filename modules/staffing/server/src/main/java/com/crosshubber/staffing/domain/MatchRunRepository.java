package com.crosshubber.staffing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchRunRepository extends JpaRepository<MatchRunEntity, UUID> {

  List<MatchRunEntity> findByRfpIdOrderByCreatedAtDesc(UUID rfpId);
}
