package com.crosshubber.portal.modules.navigation.usersettings;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code navigation_user_settings} table. */
@Entity
@Table(name = "navigation_user_settings")
public class NavigationUserSettingsEntity {

  @Id
  @Column(name = "user_id")
  private String userId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
  private String settings;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    if (updatedAt == null) {
      updatedAt = Instant.now();
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
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
}
