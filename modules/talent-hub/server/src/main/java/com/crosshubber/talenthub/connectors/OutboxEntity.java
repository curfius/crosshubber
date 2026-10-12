package com.crosshubber.talenthub.connectors;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Connector outbox log (messaging/crm/hris emulators write here; viewable in settings). */
@Entity
@Table(name = "connector_outbox")
public class OutboxEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Connector key: messaging | crm | hris | ... */
  @Column(nullable = false, length = 32)
  private String kind;

  @Column(nullable = false, length = 300)
  private String subject;

  /** {to?, body, ref?, employeeId? …} */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> body;

  @Column(nullable = false, updatable = false)
  private Instant at;

  public UUID getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public Map<String, Object> getBody() {
    return body;
  }

  public void setBody(Map<String, Object> body) {
    this.body = body;
  }

  public Instant getAt() {
    return at;
  }

  @PrePersist
  void stampAt() {
    if (at == null) {
      at = Instant.now();
    }
  }
}
