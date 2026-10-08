package com.crosshubber.portal.modules.msgcenter.templates;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Immutable template version (plan §9 amendment): edits create version n+1; retired versions still
 * resolve for already-referenced senders, but are hidden from pickers.
 */
@Entity
@Table(
    name = "mc_task_template_versions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "version"}))
public class McTaskTemplateVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "template_id", nullable = false, updatable = false)
  private Long templateId;

  @Column(nullable = false, updatable = false)
  private int version;

  @Column(nullable = false, length = 16)
  private String kind;

  @Column(nullable = false, length = 16)
  private String completion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fields_json", nullable = false, columnDefinition = "jsonb", updatable = false)
  private String fieldsJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sections_json", columnDefinition = "jsonb", updatable = false)
  private String sectionsJson;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "created_by", length = 128)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getTemplateId() {
    return templateId;
  }

  public void setTemplateId(Long templateId) {
    this.templateId = templateId;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getCompletion() {
    return completion;
  }

  public void setCompletion(String completion) {
    this.completion = completion;
  }

  public String getFieldsJson() {
    return fieldsJson;
  }

  public void setFieldsJson(String fieldsJson) {
    this.fieldsJson = fieldsJson;
  }

  public String getSectionsJson() {
    return sectionsJson;
  }

  public void setSectionsJson(String sectionsJson) {
    this.sectionsJson = sectionsJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
