package com.crosshubber.portal.modules.aihub.dto;

import com.crosshubber.portal.modules.aihub.context.ClientContext;

/**
 * Chat stream request. {@code context} is the optional client-supplied session context pack (AI
 * plan A2 — missing context = pre-context behavior). {@code toolConfirmation} confirms a pending
 * mutating tool call (AI plan B6): the pending call is executed at the start of the turn and its
 * result is folded into the conversation.
 */
public record ChatStreamRequest(
    String conversationId,
    String message,
    ClientContext context,
    ToolConfirmation toolConfirmation) {

  /** Confirmation of a previously offered mutating tool call. */
  public record ToolConfirmation(String callId) {}
}
