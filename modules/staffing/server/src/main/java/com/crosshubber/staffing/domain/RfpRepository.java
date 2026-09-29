package com.crosshubber.staffing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RfpRepository extends JpaRepository<RfpEntity, UUID> {

  List<RfpEntity> findByStatus(RfpStatus status);

  List<RfpEntity> findByStatusNotOrderByCreatedAtDesc(RfpStatus status);
}
