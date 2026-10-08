package com.crosshubber.portal.modules.msgcenter.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto;

/**
 * Inbox read endpoints (plan §6 web slice) — authenticated-user level; audience filtering is the
 * gate (the query service predicate decides what a caller sees).
 */
@RestController
public class MsgCenterInboxController {

  private final MsgCenterInboxService service;

  public MsgCenterInboxController(MsgCenterInboxService service) {
    this.service = service;
  }

  /**
   * Cursor-paged inbox: optional {@code type} (notification|message|task), {@code status}
   * (open|claimed|done), {@code cursorAt}/{@code cursorId}, {@code limit}.
   */
  @GetMapping("/api/msgcenter/messages")
  public Map<String, Object> messages(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursorAt,
      @RequestParam(required = false) Long cursorId,
      @RequestParam(required = false) Integer limit) {
    List<InboxItemDto> items = service.listOwn(type, status, cursorAt, cursorId, limit);
    return Map.of("items", items);
  }

  /** Unread count for the sidebar badge (audience-matched, no read marker). */
  @GetMapping("/api/msgcenter/unread")
  public Map<String, Object> unread() {
    return Map.of("unread", service.unread());
  }

  /** Marks one visible message read (idempotent). */
  @PostMapping("/api/msgcenter/messages/{id}/read")
  public void markRead(@PathVariable long id) {
    service.markRead(id);
  }
}
