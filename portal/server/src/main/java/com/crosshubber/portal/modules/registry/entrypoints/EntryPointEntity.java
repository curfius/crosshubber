package com.crosshubber.portal.modules.registry.entrypoints;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** JPA entity for {@code entry_points} table. */
@Entity
@Table(
    name = "entry_points",
    uniqueConstraints = @UniqueConstraint(columnNames = {"module_key", "entry_key"}))
public class EntryPointEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "module_key", nullable = false)
  private String moduleKey;

  @Column(name = "entry_key", nullable = false)
  private String entryKey;

  @Column(name = "category", nullable = false)
  private String category;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "type", nullable = false)
  private String type;

  @Column(name = "url")
  private String url;

  @Column(name = "sandbox", columnDefinition = "text")
  private String sandbox;

  @Column(name = "allow")
  private String allow;

  @Column(name = "load_path")
  private String loadPath;

  @Column(name = "entry_url")
  private String entryUrl;

  @Column(name = "element")
  private String element;

  @Column(name = "parent_entry_key")
  private String parentEntryKey;

  @Column(name = "group_key")
  private String groupKey;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;

  @Column(name = "roles", columnDefinition = "text")
  private String roles;

  @Column(name = "active", nullable = false)
  private Boolean active;

  @Column(name = "icon")
  private String icon;

  @Column(name = "color")
  private String color;

  @Column(name = "multi", nullable = false)
  private Boolean multi;

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
    if (active == null) {
      active = true;
    }
    if (multi == null) {
      multi = false;
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

  public String getModuleKey() {
    return moduleKey;
  }

  public void setModuleKey(String moduleKey) {
    this.moduleKey = moduleKey;
  }

  public String getEntryKey() {
    return entryKey;
  }

  public void setEntryKey(String entryKey) {
    this.entryKey = entryKey;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getSandbox() {
    return sandbox;
  }

  public void setSandbox(String sandbox) {
    this.sandbox = sandbox;
  }

  public String getAllow() {
    return allow;
  }

  public void setAllow(String allow) {
    this.allow = allow;
  }

  public String getLoadPath() {
    return loadPath;
  }

  public void setLoadPath(String loadPath) {
    this.loadPath = loadPath;
  }

  public String getEntryUrl() {
    return entryUrl;
  }

  public void setEntryUrl(String entryUrl) {
    this.entryUrl = entryUrl;
  }

  public String getElement() {
    return element;
  }

  public void setElement(String element) {
    this.element = element;
  }

  public String getParentEntryKey() {
    return parentEntryKey;
  }

  public void setParentEntryKey(String parentEntryKey) {
    this.parentEntryKey = parentEntryKey;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
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

  public String getIcon() {
    return icon;
  }

  public void setIcon(String icon) {
    this.icon = icon;
  }

  public String getColor() {
    return color;
  }

  public void setColor(String color) {
    this.color = color;
  }

  public Boolean getMulti() {
    return multi;
  }

  public void setMulti(Boolean multi) {
    this.multi = multi;
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
