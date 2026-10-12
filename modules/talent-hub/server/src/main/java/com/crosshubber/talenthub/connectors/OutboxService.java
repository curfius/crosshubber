package com.crosshubber.talenthub.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Outbox log service (P4.1/P4.2): appends emulator-side sends/calls as inspectable rows; the
 * settings UI lists them. Used by the emulated messaging/crm/hris connectors.
 */
@Service
public class OutboxService {

  private final OutboxRepository repo;

  public OutboxService(OutboxRepository repo) {
    this.repo = repo;
  }

  public void append(String kind, String subject, Map<String, Object> body) {
    OutboxEntity row = new OutboxEntity();
    row.setKind(kind);
    row.setSubject(subject);
    row.setBody(new LinkedHashMap<>(body == null ? Map.of() : body));
    repo.save(row);
  }

  public List<OutboxEntity> latest(String kind) {
    return kind == null || kind.isBlank()
        ? repo.findTop50ByOrderByAtDesc()
        : repo.findTop50ByKindOrderByAtDesc(kind);
  }

  long count() {
    return repo.count();
  }
}
