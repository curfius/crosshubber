package com.crosshubber.solutions.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Immutable workflow-transition audit row — never updated, never deleted. */
@Entity
@Table(name = "stage_event")
public class StageEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private ProjectEntity project;

  @Column(name = "from_stage", nullable = false, length = 32)
  private String fromStage;

  @Column(name = "to_stage", nullable = false, length = 32)
  private String toStage;

  @Column(nullable = false, length = 120)
  private String actor;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @PrePersist
  void stampOccurredAt() {
    if (occurredAt == null) {
      occurredAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public ProjectEntity getProject() {
    return project;
  }

  public void setProject(ProjectEntity project) {
    this.project = project;
  }

  public String getFromStage() {
    return fromStage;
  }

  public void setFromStage(String fromStage) {
    this.fromStage = fromStage;
  }

  public String getToStage() {
    return toStage;
  }

  public void setToStage(String toStage) {
    this.toStage = toStage;
  }

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
