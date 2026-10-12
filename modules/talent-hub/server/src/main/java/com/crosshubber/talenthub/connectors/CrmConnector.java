package com.crosshubber.talenthub.connectors;

import java.util.UUID;

/** CRM port (P4.1): client lookup + fake opportunity creation; real adapters land with demand. */
public interface CrmConnector extends ModuleConnector {

  /** Finds a client by name (null when unknown) and returns the CRM external id. */
  String findClient(String name);

  /** Logs an opportunity creation for the client; returns a CRM-side reference. */
  String logOpportunity(String clientName, String note);

  static String newRef() {
    return "crm-" + UUID.randomUUID();
  }
}
