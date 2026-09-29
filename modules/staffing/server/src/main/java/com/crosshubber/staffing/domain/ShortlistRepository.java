package com.crosshubber.staffing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShortlistRepository extends JpaRepository<ShortlistEntity, UUID> {

  List<ShortlistEntity> findByRfpIdOrderByCreatedAtDesc(UUID rfpId);
}
