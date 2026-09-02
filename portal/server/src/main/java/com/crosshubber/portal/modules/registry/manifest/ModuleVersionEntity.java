package com.crosshubber.portal.modules.registry.manifest;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** JPA entity for {@code module_versions} table. */
@Entity
@Table(name = "module_versions")
public class ModuleVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "module_key", nullable = false)
  private String moduleKey;

  @Column(name = "version", nullable = false)
  private String version;

  @Column(name = "digest", nullable = false)
  private String digest;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest", columnDefinition = "jsonb", nullable = false)
  private String manifest;

  @Column(name = "installed_at", nullable = false)
  private Instant installedAt;

  @Column(name = "installed_by", nullable = false)
  private String installedBy;

  @Column(name = "status", nullable = false)
  private String status;

  @PrePersist
  void prePersist() {
    if (installedAt == null) {
      installedAt = Instant.now();
    }
    if (status == null) {
      status = "active";
    }
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public void setModuleKey(String moduleKey) {
    this.moduleKey = moduleKey;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getDigest() {
    return digest;
  }

  public void setDigest(String digest) {
    this.digest = digest;
  }

  public String getManifest() {
    return manifest;
  }

  public void setManifest(String manifest) {
    this.manifest = manifest;
  }

  public Instant getInstalledAt() {
    return installedAt;
  }

  public void setInstalledAt(Instant installedAt) {
    this.installedAt = installedAt;
  }

  public String getInstalledBy() {
    return installedBy;
  }

  public void setInstalledBy(String installedBy) {
    this.installedBy = installedBy;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
