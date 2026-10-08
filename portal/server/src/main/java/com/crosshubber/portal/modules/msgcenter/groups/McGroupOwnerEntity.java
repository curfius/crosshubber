package com.crosshubber.portal.modules.msgcenter.groups;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Group owner row (owners manage closed-group membership and receive churn notifications). */
@Entity
@Table(
    name = "mc_group_owners",
    uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "user_sub"}))
public class McGroupOwnerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private Long groupId;

  @Column(name = "user_sub", nullable = false, updatable = false, length = 128)
  private String userSub;

  protected McGroupOwnerEntity() {}

  public McGroupOwnerEntity(Long groupId, String userSub) {
    this.groupId = groupId;
    this.userSub = userSub;
  }

  public Long getId() {
    return id;
  }

  public Long getGroupId() {
    return groupId;
  }

  public String getUserSub() {
    return userSub;
  }
}
