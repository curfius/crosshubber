package com.crosshubber.portal.modules.i18n.settings;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code i18n_settings} table (singleton row id=1). */
@Entity
@Table(name = "i18n_settings")
public class I18nSettingsEntity {

  @Id
  @Column(name = "id")
  private Integer id = 1;

  @Column(name = "default_language", nullable = false)
  private String defaultLanguage;

  @Column(name = "fallback_language", nullable = false)
  private String fallbackLanguage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "overrides", columnDefinition = "jsonb", nullable = false)
  private String overrides;

  @Column(name = "content_version", nullable = false)
  private Integer contentVersion;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    if (updatedAt == null) {
      updatedAt = Instant.now();
    }
    if (contentVersion == null) {
      contentVersion = 1;
    }
    if (overrides == null) {
      overrides = "{}";
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public String getDefaultLanguage() {
    return defaultLanguage;
  }

  public void setDefaultLanguage(String defaultLanguage) {
    this.defaultLanguage = defaultLanguage;
  }

  public String getFallbackLanguage() {
    return fallbackLanguage;
  }

  public void setFallbackLanguage(String fallbackLanguage) {
    this.fallbackLanguage = fallbackLanguage;
  }

  public String getOverrides() {
    return overrides;
  }

  public void setOverrides(String overrides) {
    this.overrides = overrides;
  }

  public Integer getContentVersion() {
    return contentVersion;
  }

  public void setContentVersion(Integer contentVersion) {
    this.contentVersion = contentVersion;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
