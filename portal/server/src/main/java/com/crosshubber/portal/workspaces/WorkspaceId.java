package com.crosshubber.portal.workspaces;

import java.io.Serializable;
import java.util.Objects;

/** Composite PK for {@link WorkspaceEntity} (userId + name). */
public class WorkspaceId implements Serializable {

  private String userId;
  private String name;

  public WorkspaceId() {}

  public WorkspaceId(String userId, String name) {
    this.userId = userId;
    this.name = name;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    WorkspaceId that = (WorkspaceId) o;
    return Objects.equals(userId, that.userId) && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userId, name);
  }
}
