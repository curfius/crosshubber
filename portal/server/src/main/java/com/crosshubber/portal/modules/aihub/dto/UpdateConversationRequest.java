package com.crosshubber.portal.modules.aihub.dto;

/** Partial update for a conversation — absent fields are left unchanged. */
public record UpdateConversationRequest(String title, Boolean pinned) {}
