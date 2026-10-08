package com.crosshubber.portal.modules.msgcenter.groups;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Membership row — unique per (group, user); carries the Phase 7 per-group email opt-in flag. */
@Entity
@Table(
    name = "mc_group_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "user_sub"}))
public class McGroupMemberEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private Long groupId;

  @Column(name = "user_sub", nullable = false, updatable = false, length = 128)
  private String userSub;

  @Column(name = "email_flag", nullable = false)
  private boolean emailFlag;

  /** 'self' | owner sub | 'admin'. */
  @Column(name = "added_by", nullable = false, length = 128)
  private String addedBy;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  protected McGroupMemberEntity() {}

  public McGroupMemberEntity(Long groupId, String userSub, String addedBy) {
    this.groupId = groupId;
    this.userSub = userSub;
    this.addedBy = addedBy;
    this.joinedAt = Instant.now();
  }

  @PrePersist
  void stampJoinedAt() {
    if (joinedAt == null) {
      joinedAt = Instant.now();
    }
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

  public boolean isEmailFlag() {
    return emailFlag;
  }

  public void setEmailFlag(boolean emailFlag) {
    this.emailFlag = emailFlag;
  }

  public String getAddedBy() {
    return addedBy;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }
}
