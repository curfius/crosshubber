package com.crosshubber.portal.modules.aihub.channels;

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

/** JPA entity for {@code ai_hub_channels} table. */
@Entity
@Table(name = "ai_hub_channels")
public class AiHubChannelEntity implements Persistable<String> {

  @Id
  @Column(name = "id")
  private String id;

  @Column(name = "type", nullable = false)
  private String type;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @Column(name = "credentials_encrypted")
  private String credentialsEncrypted;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "credentials_meta", columnDefinition = "jsonb", nullable = false)
  private String credentialsMeta;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", columnDefinition = "jsonb", nullable = false)
  private String config;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "status", columnDefinition = "jsonb", nullable = false)
  private String status;

  @Column(name = "delivery_mode", nullable = false)
  private String deliveryMode;

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
      enabled = false;
    }
    if (credentialsMeta == null) {
      credentialsMeta = "{}";
    }
    if (config == null) {
      config = "{}";
    }
    if (status == null) {
      status = "{}";
    }
    if (deliveryMode == null) {
      deliveryMode = "webhook";
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

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public String getCredentialsEncrypted() {
    return credentialsEncrypted;
  }

  public void setCredentialsEncrypted(String credentialsEncrypted) {
    this.credentialsEncrypted = credentialsEncrypted;
  }

  public String getCredentialsMeta() {
    return credentialsMeta;
  }

  public void setCredentialsMeta(String credentialsMeta) {
    this.credentialsMeta = credentialsMeta;
  }

  public String getConfig() {
    return config;
  }

  public void setConfig(String config) {
    this.config = config;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDeliveryMode() {
    return deliveryMode;
  }

  public void setDeliveryMode(String deliveryMode) {
    this.deliveryMode = deliveryMode;
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
