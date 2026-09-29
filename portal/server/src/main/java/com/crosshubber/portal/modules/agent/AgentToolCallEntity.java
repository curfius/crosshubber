package com.crosshubber.portal.modules.agent;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Audit trail for agent tool dispatches (AI plan B5): one row per dispatch attempt — user,
 * conversation, tool, argument summary, outcome and duration. Read-only from the UI (Phase C4);
 * rows are never updated or deleted.
 */
@Entity
@Table(name = "agent_tool_calls")
public class AgentToolCallEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "user_id", nullable = false, updatable = false, length = 128)
  private String userId;

  @Column(name = "conversation_id", updatable = false, length = 64)
  private String conversationId;

  @Column(name = "module_key", updatable = false, length = 64)
  private String moduleKey;

  @Column(name = "tool_id", nullable = false, updatable = false, length = 160)
  private String toolId;

  @Column(name = "tool_name", nullable = false, updatable = false, length = 96)
  private String toolName;

  /** Truncated JSON summary of the arguments (bounded, no guarantee of completeness). */
  @Column(name = "args_summary", updatable = false, columnDefinition = "text")
  private String argsSummary;

  /** ok | denied | error | needs_confirmation | confirmed | cap_reached. */
  @Column(nullable = false, updatable = false, length = 32)
  private String outcome;

  @Column(name = "duration_ms", updatable = false)
  private Integer durationMs;

  public AgentToolCallEntity() {}

  public static AgentToolCallEntity of(
      String userId,
      String conversationId,
      AgentTool tool,
      String argsSummary,
      String outcome,
      int durationMs) {
    AgentToolCallEntity entity = new AgentToolCallEntity();
    entity.userId = userId;
    entity.conversationId = conversationId;
    entity.moduleKey = tool.moduleKey();
    entity.toolId = tool.id();
    entity.toolName = tool.name();
    entity.argsSummary = argsSummary;
    entity.outcome = outcome;
    entity.durationMs = durationMs;
    return entity;
  }

  @PrePersist
  void stampOccurredAt() {
    if (occurredAt == null) {
      occurredAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getUserId() {
    return userId;
  }

  public String getConversationId() {
    return conversationId;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public String getToolId() {
    return toolId;
  }

  public String getToolName() {
    return toolName;
  }

  public String getArgsSummary() {
    return argsSummary;
  }

  public String getOutcome() {
    return outcome;
  }

  public Integer getDurationMs() {
    return durationMs;
  }
}
