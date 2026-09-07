package com.crosshubber.portal.modules.aihub;

/** Unified chat message used across portal chat, channel pipeline, and conversation history. */
public record ChatMessage(String role, String content) {}
