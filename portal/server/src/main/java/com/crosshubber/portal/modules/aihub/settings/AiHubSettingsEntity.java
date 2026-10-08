package com.crosshubber.portal.modules.aihub.settings;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** JPA entity for the single-row {@code ai_hub_settings} table. */
@Entity
@Table(name = "ai_hub_settings")
public class AiHubSettingsEntity {

  @Id
  @Column(name = "id")
  private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
  private String settings;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic-lock version — concurrent writes fail with 409. */
  @Version
  @Column(name = "version", nullable = false)
  private Integer version;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getSettings() {
    return settings;
  }

  public void setSettings(String settings) {
    this.settings = settings;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }
}
