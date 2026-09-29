package com.crosshubber.solutions.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

  List<ProjectEntity> findByStage(Stage stage);

  List<ProjectEntity> findByHealth(String health);

  List<ProjectEntity> findByClientId(UUID clientId);
}
