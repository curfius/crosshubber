package com.crosshubber.portal.modules.msgcenter.groups;

import java.util.List;

/** Group read models — camelCase DTO contract (see AgentToolCallDto precedent). */
public final class McGroupDtos {

  private McGroupDtos() {}

  public record GroupDto(
      long id,
      String key,
      String name,
      String visibility,
      boolean retired,
      List<String> owners,
      long memberCount,
      boolean myMembership,
      boolean iAmOwner,
      boolean emailFlag) {}

  public record MembershipDto(
      String groupKey, String groupName, boolean emailFlag, boolean iAmOwner) {}
}
