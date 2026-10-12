package com.crosshubber.talenthub.connectors;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Emulated CRM (P4.2): fixture client records + fake "opportunity created" log (outbox rows).
 * Deterministic + offline.
 */
@Component
public class EmulatedCrmConnector implements CrmConnector {

  static final List<String> FIXTURE_CLIENTS = List.of("acme", "globex", "initech");

  private final OutboxService outbox;

  public EmulatedCrmConnector(OutboxService outbox) {
    this.outbox = outbox;
  }

  @Override
  public ConnectorCategory category() {
    return ConnectorCategory.CRM;
  }

  @Override
  public String kind() {
    return "emulated";
  }

  @Override
  public String findClient(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String key = name.toLowerCase().replaceAll("\\W+", "");
    return FIXTURE_CLIENTS.contains(key) ? name.trim() : null;
  }

  @Override
  public String logOpportunity(String clientName, String note) {
    String ref = CrmConnector.newRef();
    outbox.append(
        "crm",
        "opportunity created: " + (clientName == null ? "(unknown)" : clientName),
        Map.of(
            "ref",
            ref,
            "outcome",
            note == null ? "" : note,
            "client",
            clientName == null ? "" : clientName));
    return ref;
  }
}
