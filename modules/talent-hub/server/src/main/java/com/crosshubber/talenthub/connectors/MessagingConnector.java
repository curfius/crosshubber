package com.crosshubber.talenthub.connectors;

/** Messaging port (P4.1): direct sends; the emulator writes to the outbox log. */
public interface MessagingConnector extends ModuleConnector {

  /** Sends one message; no-throw contract for emulators, real adapters report failures here. */
  String send(String to, String subject, String body);
}
