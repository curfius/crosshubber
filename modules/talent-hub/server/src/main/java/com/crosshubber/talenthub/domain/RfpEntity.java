package com.crosshubber.talenthub.domain;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Engagement header (v2): phase is derived/stored (D3), {@code analysis} carries the AI capture
 * draft, requirements the approved top-level defaults. Replaces the v1 status semantics.
 */
@Entity
@Table(name = "rfp")
public class RfpEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "client_id", nullable = false)
  private UUID clientId;

  @Column(nullable = false, length = 300)
  private String title;

  /** rfp | rfq */
  @Column(nullable = false, length = 8)
  private String kind = "rfp";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private RfpPhase phase = RfpPhase.DRAFT;

  /** tm | direct (per-position override possible) */
  @Column(name = "service_type", nullable = false, length = 8)
  private String serviceType = "tm";

  /** manual | email */
  @Column(nullable = false, length = 8)
  private String source = "manual";

  @Column private Instant deadline;

  @Column(columnDefinition = "text")
  private String description;

  /** Top-level requirement defaults (positions may override). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> requirements = new HashMap<>();

  /**
   * AI capture draft: {status: configured|unconfigured|failed, positions[], openQuestions[], model,
   * at}.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> analysis;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "document_refs", nullable = false, columnDefinition = "jsonb")
  private List<String> documentRefs = new ArrayList<>();

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

  public UUID getClientId() {
    return clientId;
  }

  public void setClientId(UUID clientId) {
    this.clientId = clientId;
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

  public RfpPhase getPhase() {
    return phase;
  }

  public void setPhase(RfpPhase phase) {
    this.phase = phase;
  }

  public String getServiceType() {
    return serviceType;
  }

  public void setServiceType(String serviceType) {
    this.serviceType = serviceType;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Instant getDeadline() {
    return deadline;
  }

  public void setDeadline(Instant deadline) {
    this.deadline = deadline;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Map<String, Object> getRequirements() {
    return requirements;
  }

  public void setRequirements(Map<String, Object> requirements) {
    this.requirements = requirements;
  }

  public Map<String, Object> getAnalysis() {
    return analysis;
  }

  public void setAnalysis(Map<String, Object> analysis) {
    this.analysis = analysis;
  }

  public List<String> getDocumentRefs() {
    return documentRefs;
  }

  public void setDocumentRefs(List<String> documentRefs) {
    this.documentRefs = documentRefs;
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
