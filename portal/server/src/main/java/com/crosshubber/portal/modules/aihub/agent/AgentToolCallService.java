package com.crosshubber.portal.modules.aihub.agent;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the agent tool audit trail (AI plan B5/C4). */
@Service
public class AgentToolCallService {

  private final AgentToolCallRepository auditRepository;

  public AgentToolCallService(AgentToolCallRepository auditRepository) {
    this.auditRepository = auditRepository;
  }

  /** Latest tool dispatches across all users, newest first, bounded to 200 rows. */
  @Transactional(readOnly = true)
  public List<AgentToolCallDto> listRecent() {
    return auditRepository.findTop200ByOrderByOccurredAtDesc().stream()
        .map(AgentToolCallDto::from)
        .toList();
  }
}
