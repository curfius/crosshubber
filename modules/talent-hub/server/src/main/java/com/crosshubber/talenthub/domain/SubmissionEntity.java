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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Batch of candidate processes sent to the client at a point in time (evolves the v1 shortlist).
 */
@Entity
@Table(name = "submission")
public class SubmissionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "process_ids", nullable = false, columnDefinition = "jsonb")
  private List<UUID> processIds = new ArrayList<>();

  @Column(columnDefinition = "text")
  private String note;

  /** {outcome: accepted|rejected|..., note} recorded via the response endpoint (P2.4). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> response;

  @Column(name = "sent_by", nullable = false, length = 120)
  private String sentBy;

  @Column(name = "sent_at", nullable = false)
  private Instant sentAt;

  @Column(name = "responded_at")
  private Instant respondedAt;

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
    if (sentAt == null) {
      sentAt = now;
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

  public List<UUID> getProcessIds() {
    return processIds;
  }

  public void setProcessIds(List<UUID> processIds) {
    this.processIds = processIds;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public Map<String, Object> getResponse() {
    return response;
  }

  public void setResponse(Map<String, Object> response) {
    this.response = response;
  }

  public String getSentBy() {
    return sentBy;
  }

  public void setSentBy(String sentBy) {
    this.sentBy = sentBy;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public void setSentAt(Instant sentAt) {
    this.sentAt = sentAt;
  }

  public Instant getRespondedAt() {
    return respondedAt;
  }

  public void setRespondedAt(Instant respondedAt) {
    this.respondedAt = respondedAt;
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
