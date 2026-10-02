package com.crosshubber.solutions.settings;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One settings group (key = {@code agent} | {@code tools} | {@code rag}), value = JSON blob. */
@Entity
@Table(name = "module_settings")
public class ModuleSettingEntity {

  @Id
  @Column(name = "key", nullable = false, length = 64)
  private String key;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "value", columnDefinition = "jsonb", nullable = false)
  private String value;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
