package com.crosshubber.staffing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import jakarta.persistence.Table;

/** A matching run: deterministic scoring over parsed candidate profiles for one RFP. */
@Entity
@Table(name = "match_run")
public class MatchRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  /** {topN} */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> params = new HashMap<>();

  /** running | done | failed */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private RunStatus status = RunStatus.RUNNING;

  /** [{candidateId, name, score, rationale}] ranked by score desc. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<Map<String, Object>> results = new ArrayList<>();

  @Column(name = "created_by", nullable = false, length = 120)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void stampCreatedAt() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
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

  public Map<String, Object> getParams() {
    return params;
  }

  public void setParams(Map<String, Object> params) {
    this.params = params;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public List<Map<String, Object>> getResults() {
    return results;
  }

  public void setResults(List<Map<String, Object>> results) {
    this.results = results;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public enum RunStatus {
    RUNNING,
    DONE,
    FAILED
  }
}
