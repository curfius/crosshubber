package com.crosshubber.talenthub.connectors;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Outbox log rows (emulated connector writes; viewable in the settings UI). */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

  List<OutboxEntity> findTop50ByKindOrderByAtDesc(String kind);

  List<OutboxEntity> findTop50ByOrderByAtDesc();
}
