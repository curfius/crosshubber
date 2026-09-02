package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** JPA entity for {@code navigation_pinned_apps} table. */
@Entity
@Table(name = "navigation_pinned_apps")
public class NavigationPinnedAppEntity implements Persistable<UUID> {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "parent_id", columnDefinition = "uuid")
  private UUID parentId;

  @Column(name = "node_type", nullable = false)
  private String nodeType;

  @Column(name = "name")
  private String name;

  @Column(name = "ref")
  private String ref;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (sortOrder == null) {
      sortOrder = 0;
    }
  }

  @Transient private boolean isNew = true;

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public UUID getParentId() {
    return parentId;
  }

  public void setParentId(UUID parentId) {
    this.parentId = parentId;
  }

  public String getNodeType() {
    return nodeType;
  }

  public void setNodeType(String nodeType) {
    this.nodeType = nodeType;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
