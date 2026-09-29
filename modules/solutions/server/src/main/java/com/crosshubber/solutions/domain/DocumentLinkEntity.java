package com.crosshubber.solutions.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "document_link",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "document_ref"}))
public class DocumentLinkEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  private ProjectEntity project;

  @Column(name = "document_ref", nullable = false, length = 512)
  private String documentRef;

  @Column(nullable = false, length = 300)
  private String title;

  @Column(name = "rag_enabled", nullable = false)
  private boolean ragEnabled;

  @Column(name = "added_by", length = 120)
  private String addedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void stampCreatedAt() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public ProjectEntity getProject() {
    return project;
  }

  public void setProject(ProjectEntity project) {
    this.project = project;
  }

  public String getDocumentRef() {
    return documentRef;
  }

  public void setDocumentRef(String documentRef) {
    this.documentRef = documentRef;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public boolean isRagEnabled() {
    return ragEnabled;
  }

  public void setRagEnabled(boolean ragEnabled) {
    this.ragEnabled = ragEnabled;
  }

  public String getAddedBy() {
    return addedBy;
  }

  public void setAddedBy(String addedBy) {
    this.addedBy = addedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
