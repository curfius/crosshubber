package com.crosshubber.staffing.domain;

import java.time.Instant;
import java.util.HashMap;
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

@Entity
@Table(name = "rfp")
public class RfpEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 200)
  private String client;

  @Column(nullable = false, length = 300)
  private String title;

  /** rfp | rfq */
  @Column(nullable = false, length = 8)
  private String kind = "rfp";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private RfpStatus status = RfpStatus.NEW;

  @Column(nullable = false)
  private Instant deadline;

  /** Extracted requirements: {skills[], seniority, languages[], description}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> requirements = new HashMap<>();

  @Column(name = "spec_doc_ref", length = 512)
  private String specDocRef;

  @Column(nullable = false, length = 120)
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

  public String getClient() {
    return client;
  }

  public void setClient(String client) {
    this.client = client;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public RfpStatus getStatus() {
    return status;
  }

  public void setStatus(RfpStatus status) {
    this.status = status;
  }

  public Instant getDeadline() {
    return deadline;
  }

  public void setDeadline(Instant deadline) {
    this.deadline = deadline;
  }

  public Map<String, Object> getRequirements() {
    return requirements;
  }

  public void setRequirements(Map<String, Object> requirements) {
    this.requirements = requirements;
  }

  public String getSpecDocRef() {
    return specDocRef;
  }

  public void setSpecDocRef(String specDocRef) {
    this.specDocRef = specDocRef;
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
