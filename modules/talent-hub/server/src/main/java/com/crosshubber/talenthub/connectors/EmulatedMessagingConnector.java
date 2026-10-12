package com.crosshubber.talenthub.connectors;

import org.springframework.stereotype.Component;

/** Emulated messaging (P4.2): sends become outbox rows visible in the settings UI. */
@Component
public class EmulatedMessagingConnector implements MessagingConnector {

  private final OutboxService outbox;

  public EmulatedMessagingConnector(OutboxService outbox) {
    this.outbox = outbox;
  }

  @Override
  public ConnectorCategory category() {
    return ConnectorCategory.MESSAGING;
  }

  @Override
  public String kind() {
    return "emulated";
  }

  @Override
  public String send(String to, String subject, String body) {
    String ref = CrmConnector.newRef();
    outbox.append(
        "messaging",
        subject == null || subject.isBlank() ? "(no subject)" : subject,
        java.util.Map.of("to", to == null ? "" : to, "ref", ref, "body", body == null ? "" : body));
    return ref;
  }
}
