package com.crosshubber.talenthub.docs;

/**
 * Module-owned document-source port (AI_MODULES_PLAN "Module-owned datasources"): the module
 * implements its own connectors and holds its own credentials — the portal hosts none. Adapter
 * selection is config-driven ({@code talenthub.docsource.kind}); the scaffold ships {@code fake},
 * real adapters land behind the P4 connector registry.
 */
public interface DocumentSource extends com.crosshubber.talenthub.connectors.ModuleConnector {

  @Override
  default com.crosshubber.talenthub.connectors.ConnectorCategory category() {
    return com.crosshubber.talenthub.connectors.ConnectorCategory.DOCUMENT_STORE;
  }

  /** Fetched document content (text extracted when the format supports it). */
  record DocumentContent(String name, String mimeType, long size, String text) {}

  /**
   * Fetches a document by ref (format: adapter-specific).
   *
   * @throws Exception on transport/auth failure — callers convert to error payloads
   */
  DocumentContent fetchDocument(String documentRef) throws Exception;
}
