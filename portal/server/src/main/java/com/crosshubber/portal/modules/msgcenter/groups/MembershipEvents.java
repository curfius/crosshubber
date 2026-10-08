package com.crosshubber.portal.modules.msgcenter.groups;

/** Membership churn reasons emitted to owners (dogfooded as msgcenter messages). */
public enum MembershipEvents {
  JOIN,
  LEAVE,
  ADDED,
  REMOVED
}
