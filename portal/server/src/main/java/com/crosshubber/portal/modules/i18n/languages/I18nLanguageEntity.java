package com.crosshubber.portal.modules.i18n.languages;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code i18n_languages} table. */
@Entity
@Table(name = "i18n_languages")
public class I18nLanguageEntity {

  @Id
  @Column(name = "code")
  private String code;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "native_name", nullable = false)
  private String nativeName;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @Column(name = "seeded", nullable = false)
  private Boolean seeded;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
    if (enabled == null) {
      enabled = true;
    }
    if (seeded == null) {
      seeded = true;
    }
    if (sortOrder == null) {
      sortOrder = 100;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getNativeName() {
    return nativeName;
  }

  public void setNativeName(String nativeName) {
    this.nativeName = nativeName;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public Boolean getSeeded() {
    return seeded;
  }

  public void setSeeded(Boolean seeded) {
    this.seeded = seeded;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
