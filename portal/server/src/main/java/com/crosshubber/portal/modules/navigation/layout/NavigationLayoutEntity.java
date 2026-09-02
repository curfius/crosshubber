package com.crosshubber.portal.modules.navigation.layout;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code navigation_layout} table (singleton id=1). */
@Entity
@Table(name = "navigation_layout")
public class NavigationLayoutEntity {

  @Id
  @Column(name = "id")
  private Integer id = 1;

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

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
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
}
