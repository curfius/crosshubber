package com.crosshubber.staffing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Shortlist: the curated candidate set submitted for an RFP. */
@Entity
@Table(name = "shortlist")
public class ShortlistEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<UUID> candidateIds = new ArrayList<>();

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

  public List<UUID> getCandidateIds() {
    return candidateIds;
  }

  public void setCandidateIds(List<UUID> candidateIds) {
    this.candidateIds = candidateIds;
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
}
