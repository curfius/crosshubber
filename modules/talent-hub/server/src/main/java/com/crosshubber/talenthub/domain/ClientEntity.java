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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Client intelligence (STAFFING_MODULE_PLAN §7): MVP module-owned; CRM adapter enriches later. */
@Entity
@Table(name = "client")
public class ClientEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 120)
  private String industry;

  @Column(name = "account_owner", length = 120)
  private String accountOwner;

  /** [{name, role, email, phone}] */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<Map<String, Object>> contacts = new ArrayList<>();

  @Column(columnDefinition = "text")
  private String notes;

  /** {crmId: "...", ...} when a CRM connector is configured. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "external_refs", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> externalRefs = new HashMap<>();

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

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getIndustry() {
    return industry;
  }

  public void setIndustry(String industry) {
    this.industry = industry;
  }

  public String getAccountOwner() {
    return accountOwner;
  }

  public void setAccountOwner(String accountOwner) {
    this.accountOwner = accountOwner;
  }

  public List<Map<String, Object>> getContacts() {
    return contacts;
  }

  public void setContacts(List<Map<String, Object>> contacts) {
    this.contacts = contacts;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public Map<String, Object> getExternalRefs() {
    return externalRefs;
  }

  public void setExternalRefs(Map<String, Object> externalRefs) {
    this.externalRefs = externalRefs;
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
