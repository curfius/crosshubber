package com.crosshubber.portal.modules.navigation.groups;

import java.time.Instant;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** JPA entity for {@code navigation_groups} table (shell-nav sections). */
@Entity
@Table(name = "navigation_groups")
public class NavigationGroupEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "group_key", nullable = false, unique = true)
  private String groupKey;

  @Convert(converter = ModuleContentCategory.DbConverter.class)
  @Column(name = "category", nullable = false)
  private ModuleContentCategory category;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "parent_key")
  private String parentKey;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;

  @Column(name = "icon")
  private String icon;

  @Column(name = "roles", columnDefinition = "text")
  private String roles;

  /** Nav-tree eye toggle: hidden sections hide all module content within them. */
  @Column(name = "hidden", nullable = false)
  private Boolean hidden = false;

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
    if (sortOrder == null) {
      sortOrder = 0;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public ModuleContentCategory getCategory() {
    return category;
  }

  public void setCategory(ModuleContentCategory category) {
    this.category = category;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getParentKey() {
    return parentKey;
  }

  public void setParentKey(String parentKey) {
    this.parentKey = parentKey;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
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

  public Boolean getHidden() {
    return hidden;
  }

  public void setHidden(Boolean hidden) {
    this.hidden = Boolean.TRUE.equals(hidden);
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
