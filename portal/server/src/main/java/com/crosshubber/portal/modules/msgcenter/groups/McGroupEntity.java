package com.crosshubber.portal.modules.msgcenter.groups;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Portal-managed recipient group (plan §8 amendment): membership is the subscription. */
@Entity
@Table(name = "mc_groups")
public class McGroupEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String key;

  @Column(nullable = false, length = 100)
  private String name;

  /** open = users self-join/leave freely; closed = owners manage members (self-leave stays). */
  @Column(nullable = false, length = 16)
  private String visibility = "open";

  @Column(name = "created_by", length = 128)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "retired_at")
  private Instant retiredAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public Long getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getVisibility() {
    return visibility;
  }

  public void setVisibility(String visibility) {
    this.visibility = visibility;
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

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getRetiredAt() {
    return retiredAt;
  }

  public void setRetiredAt(Instant retiredAt) {
    this.retiredAt = retiredAt;
  }

  public long getVersion() {
    return version;
  }

  /** Live groups only — retired ones never match audiences. */
  public boolean isLive() {
    return retiredAt == null;
  }
}
