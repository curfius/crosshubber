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
 * Talent pool record: parsed profile + kind (internal consultant | external), assignment/bench,
 * expectations, contact and consent (STAFFING_MODULE_PLAN §6). Enrichment status via
 * lastEnrichedAt.
 */
@Entity
@Table(name = "candidate_profile")
public class CandidateProfileEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "source_ref", nullable = false, length = 512)
  private String sourceRef;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 300)
  private String headline;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<String> skills = new ArrayList<>();

  @Column(length = 32)
  private String seniority;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<String> languages = new ArrayList<>();

  @Column(length = 120)
  private String availability;

  /** parsed | stale */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private ProfileStatus status = ProfileStatus.PARSED;

  @Column(name = "parsed_at", nullable = false)
  private Instant parsedAt;

  /** internal | external */
  @Column(nullable = false, length = 8)
  private String kind = "external";

  /** {email, phone, linkedin, ...} (emulated source until real intake exists). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> contact = new HashMap<>();

  /**
   * {project, client, allocationPct, startAt, expectedEndAt, benchSince} — internal consultants;
   * null for external pipeline candidates.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> assignment;

  /** {rate, currency, location, remote, noticePeriod, motivation}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> expectations;

  /** {outreach: allowed|..., at, note} — GDPR-consent gating. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> consent;

  @Column(name = "last_enriched_at")
  private Instant lastEnrichedAt;

  @Column(columnDefinition = "text")
  private String notes;

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
    if (parsedAt == null) {
      parsedAt = now;
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

  public String getSourceRef() {
    return sourceRef;
  }

  public void setSourceRef(String sourceRef) {
    this.sourceRef = sourceRef;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getHeadline() {
    return headline;
  }

  public void setHeadline(String headline) {
    this.headline = headline;
  }

  public List<String> getSkills() {
    return skills;
  }

  public void setSkills(List<String> skills) {
    this.skills = skills;
  }

  public String getSeniority() {
    return seniority;
  }

  public void setSeniority(String seniority) {
    this.seniority = seniority;
  }

  public List<String> getLanguages() {
    return languages;
  }

  public void setLanguages(List<String> languages) {
    this.languages = languages;
  }

  public String getAvailability() {
    return availability;
  }

  public void setAvailability(String availability) {
    this.availability = availability;
  }

  public ProfileStatus getStatus() {
    return status;
  }

  public void setStatus(ProfileStatus status) {
    this.status = status;
  }

  public Instant getParsedAt() {
    return parsedAt;
  }

  public void setParsedAt(Instant parsedAt) {
    this.parsedAt = parsedAt;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public Map<String, Object> getContact() {
    return contact;
  }

  public void setContact(Map<String, Object> contact) {
    this.contact = contact;
  }

  public Map<String, Object> getAssignment() {
    return assignment;
  }

  public void setAssignment(Map<String, Object> assignment) {
    this.assignment = assignment;
  }

  public Map<String, Object> getExpectations() {
    return expectations;
  }

  public void setExpectations(Map<String, Object> expectations) {
    this.expectations = expectations;
  }

  public Map<String, Object> getConsent() {
    return consent;
  }

  public void setConsent(Map<String, Object> consent) {
    this.consent = consent;
  }

  public Instant getLastEnrichedAt() {
    return lastEnrichedAt;
  }

  public void setLastEnrichedAt(Instant lastEnrichedAt) {
    this.lastEnrichedAt = lastEnrichedAt;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
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

  public enum ProfileStatus {
    PARSED,
    STALE
  }
}
