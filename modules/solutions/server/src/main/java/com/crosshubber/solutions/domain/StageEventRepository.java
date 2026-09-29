package com.crosshubber.solutions.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StageEventRepository extends JpaRepository<StageEventEntity, Long> {

  List<StageEventEntity> findByProjectIdOrderByOccurredAtAsc(UUID projectId);
}
