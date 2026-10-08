package com.crosshubber.portal.modules.msgcenter.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link McMessageEntity} rows (audience filtering is SQL-side, see query service).
 */
public interface McMessageRepository extends JpaRepository<McMessageEntity, Long> {

  Optional<McMessageEntity> findByEventId(String eventId);
}
