package com.crosshubber.talenthub.connectors;

/**
 * Marker for connector beans (P4.1): every implementation declares its category + kind; the runtime
 * registry picks the active kind per category from module settings.
 */
public interface ModuleConnector {

  ConnectorCategory category();

  /** Connector kind identifier, e.g. 'emulated', 'fake', or a future product adapter name. */
  String kind();
}
