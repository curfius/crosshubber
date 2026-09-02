package com.crosshubber.portal.modules.usersettings.scopes;

import java.io.Serializable;
import java.util.Objects;

/** Composite PK for {@link UserSettingsEntity}. */
public class UserSettingsId implements Serializable {

  private String userId;
  private String scope;

  public UserSettingsId() {}

  public UserSettingsId(String userId, String scope) {
    this.userId = userId;
    this.scope = scope;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    UserSettingsId that = (UserSettingsId) o;
    return Objects.equals(userId, that.userId) && Objects.equals(scope, that.scope);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userId, scope);
  }
}
