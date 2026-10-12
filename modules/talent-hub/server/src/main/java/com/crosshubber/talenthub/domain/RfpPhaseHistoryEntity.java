package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Append-only phase change record (feeds analytics time-in-phase + funnel). */
@Entity
@Table(name = "rfp_phase_history")
public class RfpPhaseHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_phase", length = 24)
  private RfpPhase fromPhase;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_phase", nullable = false, length = 24)
  private RfpPhase toPhase;

  /** manual | derived */
  @Column(name = "`trigger`", nullable = false, length = 8)
  private String trigger;

  @Column(nullable = false, length = 120)
  private String actor;

  @Column private String note;

  @Column(name = "at", nullable = false, updatable = false)
  private Instant at;

  @PrePersist
  void stampAt() {
    if (at == null) {
      at = Instant.now();
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

  public RfpPhase getFromPhase() {
    return fromPhase;
  }

  public void setFromPhase(RfpPhase fromPhase) {
    this.fromPhase = fromPhase;
  }

  public RfpPhase getToPhase() {
    return toPhase;
  }

  public void setToPhase(RfpPhase toPhase) {
    this.toPhase = toPhase;
  }

  public String getTrigger() {
    return trigger;
  }

  public void setTrigger(String trigger) {
    this.trigger = trigger;
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

  public Instant getAt() {
    return at;
  }
}
