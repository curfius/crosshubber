package com.crosshubber.talenthub.connectors;

/**
 * HRIS port (P4.1): onboarding handshake; called outside the TX when a process reaches onboarded
 * (fail-soft), the emulator writes a fake employee-id into the outbox log.
 */
public interface HrisConnector extends ModuleConnector {

  /** Registers the onboarded person; returns the new employee id. */
  String onboard(String processRef, String personName);
}
