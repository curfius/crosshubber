package com.crosshubber.portal.workspaces;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** JPA entity for {@code workspaces} table (PK userId+name, extra uuid id). */
@Entity
@Table(name = "workspaces")
@IdClass(WorkspaceId.class)
public class WorkspaceEntity {

  @Id
  @Column(name = "user_id", nullable = false)
  private String userId;

  @Id
  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "id", columnDefinition = "uuid DEFAULT gen_random_uuid()", nullable = false)
  private UUID id;

  @Column(name = "description", nullable = false)
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "layout", columnDefinition = "jsonb")
  private String layout;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "groups", columnDefinition = "jsonb", nullable = false)
  private String groups;

  @Column(name = "focused_group_id")
  private String focusedGroupId;

  @Column(name = "hide_single_tab_toolbar", nullable = false)
  private Boolean hideSingleTabToolbar;

  @Column(name = "locked", nullable = false)
  private Boolean locked;

  @Column(name = "color", nullable = false)
  private String color;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "saved_at", nullable = false)
  private Instant savedAt;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (savedAt == null) {
      savedAt = Instant.now();
    }
    if (description == null) {
      description = "";
    }
    if (groups == null) {
      groups = "{}";
    }
    if (hideSingleTabToolbar == null) {
      hideSingleTabToolbar = false;
    }
    if (locked == null) {
      locked = false;
    }
    if (color == null) {
      color = "";
    }
    if (status == null) {
      status = "";
    }
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getLayout() {
    return layout;
  }

  public void setLayout(String layout) {
    this.layout = layout;
  }

  public String getGroups() {
    return groups;
  }

  public void setGroups(String groups) {
    this.groups = groups;
  }

  public String getFocusedGroupId() {
    return focusedGroupId;
  }

  public void setFocusedGroupId(String focusedGroupId) {
    this.focusedGroupId = focusedGroupId;
  }

  public Boolean getHideSingleTabToolbar() {
    return hideSingleTabToolbar;
  }

  public void setHideSingleTabToolbar(Boolean hideSingleTabToolbar) {
    this.hideSingleTabToolbar = hideSingleTabToolbar;
  }

  public Boolean getLocked() {
    return locked;
  }

  public void setLocked(Boolean locked) {
    this.locked = locked;
  }

  public String getColor() {
    return color;
  }

  public void setColor(String color) {
    this.color = color;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getSavedAt() {
    return savedAt;
  }

  public void setSavedAt(Instant savedAt) {
    this.savedAt = savedAt;
  }
}
