package com.crosshubber.portal.modules.aihub.agent;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link AgentToolCallEntity} (AI plan B5 audit trail). */
public interface AgentToolCallRepository extends JpaRepository<AgentToolCallEntity, Long> {

  /** Latest dispatches for the audit list (AI plan C4) — newest first, bounded. */
  List<AgentToolCallEntity> findTop200ByOrderByOccurredAtDesc();
}
