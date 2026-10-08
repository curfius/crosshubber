package com.crosshubber.portal.modules.msgcenter.web;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService;
import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto;

/**
 * Session-scoped facade for inbox reads: resolves the authenticated user and their roles, then
 * delegates to the query service. Controllers stay thin.
 */
@Service
public class MsgCenterInboxService {

  private final MsgCenterQueryService queryService;

  public MsgCenterInboxService(MsgCenterQueryService queryService) {
    this.queryService = queryService;
  }

  public List<InboxItemDto> listOwn(
      String type, String status, String cursorAt, Long cursorId, Integer limit) {
    return queryService.listOwn(
        SecurityUtils.currentUserSub(),
        SecurityUtils.currentUserRoles(),
        type,
        status,
        cursorAt,
        cursorId,
        limit);
  }

  public long unread() {
    return queryService.unread(SecurityUtils.currentUserSub(), SecurityUtils.currentUserRoles());
  }

  public void markRead(long messageId) {
    queryService.markRead(
        SecurityUtils.currentUserSub(), SecurityUtils.currentUserRoles(), messageId);
  }
}
