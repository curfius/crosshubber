package com.crosshubber.portal.modules.aihub.agent;

import java.time.Instant;

/**
 * Audit row projection for {@code GET /api/ai-hub/agent-tool-calls} (AI plan C4). Key casing
 * follows this endpoint's camelCase convention (owned by this record).
 */
public record AgentToolCallDto(
    Long id,
    Instant occurredAt,
    String userId,
    String conversationId,
    String moduleKey,
    String toolId,
    String toolName,
    String argsSummary,
    String outcome,
    Integer durationMs) {

  public static AgentToolCallDto from(AgentToolCallEntity entity) {
    return new AgentToolCallDto(
        entity.getId(),
        entity.getOccurredAt(),
        entity.getUserId(),
        entity.getConversationId(),
        entity.getModuleKey(),
        entity.getToolId(),
        entity.getToolName(),
        entity.getArgsSummary(),
        entity.getOutcome(),
        entity.getDurationMs());
  }
}
