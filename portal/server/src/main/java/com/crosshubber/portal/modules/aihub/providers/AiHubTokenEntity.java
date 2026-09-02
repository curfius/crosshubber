package com.crosshubber.portal.modules.aihub.providers;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.domain.Persistable;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** JPA entity for {@code ai_hub_tokens} table. */
@Entity
@Table(name = "ai_hub_tokens")
public class AiHubTokenEntity implements Persistable<String> {

  @Id
  @Column(name = "id")
  private String id;

  @Column(name = "provider_id", nullable = false)
  private String providerId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "encrypted_key")
  private String encryptedKey;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "models", columnDefinition = "jsonb", nullable = false)
  private String models;

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
    if (models == null) {
      models = "[]";
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

    @Transient
  private boolean isNew = true;

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }
public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getProviderId() {
    return providerId;
  }

  public void setProviderId(String providerId) {
    this.providerId = providerId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getEncryptedKey() {
    return encryptedKey;
  }

  public void setEncryptedKey(String encryptedKey) {
    this.encryptedKey = encryptedKey;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public String getModels() {
    return models;
  }

  public void setModels(String models) {
    this.models = models;
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
