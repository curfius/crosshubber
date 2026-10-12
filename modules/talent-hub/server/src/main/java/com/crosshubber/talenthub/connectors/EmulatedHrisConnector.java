package com.crosshubber.talenthub.connectors;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Emulated HRIS (P4.2): wired to the process→onboarded stage (called outside the TX, fail-soft);
 * writes a deterministic fake employee id into the outbox log.
 */
@Component
public class EmulatedHrisConnector implements HrisConnector {

  private final OutboxService outbox;

  public EmulatedHrisConnector(OutboxService outbox) {
    this.outbox = outbox;
  }

  @Override
  public ConnectorCategory category() {
    return ConnectorCategory.HRIS;
  }

  @Override
  public String kind() {
    return "emulated";
  }

  @Override
  public String onboard(String processRef, String personName) {
    String employeeId = "emp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    outbox.append(
        "hris",
        "employee record created: " + (personName == null ? "(unknown)" : personName),
        Map.of(
            "processRef", processRef == null ? "" : processRef,
            "employeeId", employeeId,
            "person", personName == null ? "" : personName));
    return employeeId;
  }
}
