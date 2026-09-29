package com.crosshubber.portal.modules.agent;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link AgentToolCallEntity} (AI plan B5 audit trail). */
public interface AgentToolCallRepository extends JpaRepository<AgentToolCallEntity, Long> {}
