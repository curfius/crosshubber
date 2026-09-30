package com.crosshubber.portal.modules.agent;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit endpoints for the agent tool loop (AI plan C4). Sits under the AI Hub settings
 * surface — the list is an admin view of every user's dispatches.
 */
@RestController
public class AgentToolCallsController {

  private final AgentToolCallService auditService;

  public AgentToolCallsController(AgentToolCallService auditService) {
    this.auditService = auditService;
  }

  /** Latest tool dispatches across all users (admin-only, newest first, max 200). */
  @GetMapping("/api/ai-hub/agent-tool-calls")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public Map<String, List<AgentToolCallDto>> listRecent() {
    return Map.of("toolCalls", auditService.listRecent());
  }
}
