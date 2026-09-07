package com.crosshubber.portal.modules.aihub.providers;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.crosshubber.portal.modules.aihub.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_hub_tokens")
@EntityListeners(AuditingEntityListener.class)
public class AiHubTokenEntity extends BaseEntity {

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
}
