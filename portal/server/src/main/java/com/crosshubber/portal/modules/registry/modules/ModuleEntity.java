package com.crosshubber.portal.modules.registry.modules;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code modules} table. */
@Entity
@Table(name = "modules")
public class ModuleEntity {

  @Id
  @Column(name = "key")
  private String key;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "icon")
  private String icon;

  @Column(name = "roles", columnDefinition = "text")
  private String roles;

  @Column(name = "active", nullable = false)
  private Boolean active;

  @Column(name = "builtin", nullable = false)
  private Boolean builtin;

  @Column(name = "version")
  private String version;

  @Column(name = "manifest_digest")
  private String manifestDigest;

  @Column(name = "managed_by", nullable = false)
  private String managedBy;

  @Column(name = "source_url")
  private String sourceUrl;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "security_roles", columnDefinition = "jsonb")
  private String securityRoles;

  @Column(name = "base_url")
  private String baseUrl;

  @Column(name = "health")
  private String health;

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
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
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

  public String getIcon() {
    return icon;
  }

  public void setIcon(String icon) {
    this.icon = icon;
  }

  public String getRoles() {
    return roles;
  }

  public void setRoles(String roles) {
    this.roles = roles;
  }

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public Boolean getBuiltin() {
    return builtin;
  }

  public void setBuiltin(Boolean builtin) {
    this.builtin = builtin;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getManifestDigest() {
    return manifestDigest;
  }

  public void setManifestDigest(String manifestDigest) {
    this.manifestDigest = manifestDigest;
  }

  public String getManagedBy() {
    return managedBy;
  }

  public void setManagedBy(String managedBy) {
    this.managedBy = managedBy;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  public String getSecurityRoles() {
    return securityRoles;
  }

  public void setSecurityRoles(String securityRoles) {
    this.securityRoles = securityRoles;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getHealth() {
    return health;
  }

  public void setHealth(String health) {
    this.health = health;
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
