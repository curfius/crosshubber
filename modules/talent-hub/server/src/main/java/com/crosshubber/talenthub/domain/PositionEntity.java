package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.HashMap;
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

/** Req line inside an RFP (answers "1 RFP, multiple positions"); requirements may override. */
@Entity
@Table(name = "position")
public class PositionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "rfp_id", nullable = false)
  private UUID rfpId;

  @Column(nullable = false, length = 300)
  private String title;

  @Column(nullable = false)
  private Integer headcount = 1;

  /** tm | direct; null = inherit the RFP service type. */
  @Column(name = "service_type", length = 8)
  private String serviceType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> requirements = new HashMap<>();

  /** open | filling | filled | cancelled */
  @Column(nullable = false, length = 16)
  private String status = "open";

  @Column(name = "order_index", nullable = false)
  private Integer orderIndex = 0;

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

  public UUID getRfpId() {
    return rfpId;
  }

  public void setRfpId(UUID rfpId) {
    this.rfpId = rfpId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public Integer getHeadcount() {
    return headcount;
  }

  public void setHeadcount(Integer headcount) {
    this.headcount = headcount;
  }

  public String getServiceType() {
    return serviceType;
  }

  public void setServiceType(String serviceType) {
    this.serviceType = serviceType;
  }

  public Map<String, Object> getRequirements() {
    return requirements;
  }

  public void setRequirements(Map<String, Object> requirements) {
    this.requirements = requirements;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Integer getOrderIndex() {
    return orderIndex;
  }

  public void setOrderIndex(Integer orderIndex) {
    this.orderIndex = orderIndex;
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
