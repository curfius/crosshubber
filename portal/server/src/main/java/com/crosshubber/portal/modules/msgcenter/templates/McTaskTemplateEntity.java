package com.crosshubber.portal.modules.msgcenter.templates;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Task template (plan §9 amendment): key + name; versions live in mc_task_template_versions. */
@Entity
@Table(name = "mc_task_templates")
public class McTaskTemplateEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String key;

  @Column(nullable = false, length = 200)
  private String name;

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

  public boolean isLive() {
    return retiredAt == null;
  }
}
