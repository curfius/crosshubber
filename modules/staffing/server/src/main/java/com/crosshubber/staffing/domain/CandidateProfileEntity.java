package com.crosshubber.staffing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/** Parsed CV profile (LLM-assisted extraction cached module-side; raw CVs always re-fetched). */
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

  @PrePersist
  void stampParsedAt() {
    if (parsedAt == null) {
      parsedAt = Instant.now();
    }
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

  public enum ProfileStatus {
    PARSED,
    STALE
  }
}
