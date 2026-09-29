package com.crosshubber.solutions.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentLinkRepository extends JpaRepository<DocumentLinkEntity, Long> {

  List<DocumentLinkEntity> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

  List<DocumentLinkEntity> findByProjectIdAndRagEnabledTrue(UUID projectId);
}
