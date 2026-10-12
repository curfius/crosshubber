package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The candidate × position sub-process (domain plan §2). Owns its stage + history; one *active*
 * process per (position, candidate) is guarded at app level (no DB unique — re-engagement).
 */
@Entity
@Table(name = "candidate_process")
public class CandidateProcessEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  @Column(name = "position_id", nullable = false)
  private UUID positionId;

  @Column(name = "candidate_id", nullable = false)
  private UUID candidateId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ProcessStage stage = ProcessStage.POOLED;

  /** [{stage, at, by, note}] append-only. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "stage_history", nullable = false, columnDefinition = "jsonb")
  private List<Map<String, Object>> stageHistory = new ArrayList<>();

  /**
   * J/E/V fit assessment produced by the configured engine (P3): {dimensions, overall, engine, at}.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> fit;

  @Column(columnDefinition = "text")
  private String notes;

  @Column(name = "created_by", nullable = false, length = 120)
  private String createdBy;

  @Version
  @Column(nullable = false)
  private Integer version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void stampCreated() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void stampUpdated() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getRfpId() {
    return rfpId;
  }

  public void setRfpId(UUID rfpId) {
    this.rfpId = rfpId;
  }

  public UUID getPositionId() {
    return positionId;
  }

  public void setPositionId(UUID positionId) {
    this.positionId = positionId;
  }

  public UUID getCandidateId() {
    return candidateId;
  }

  public void setCandidateId(UUID candidateId) {
    this.candidateId = candidateId;
  }

  public ProcessStage getStage() {
    return stage;
  }

  public void setStage(ProcessStage stage) {
    this.stage = stage;
  }

  public List<Map<String, Object>> getStageHistory() {
    return stageHistory;
  }

  public void setStageHistory(List<Map<String, Object>> stageHistory) {
    this.stageHistory = stageHistory;
  }

  public Map<String, Object> getFit() {
    return fit;
  }

  public void setFit(Map<String, Object> fit) {
    this.fit = fit;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Integer getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
