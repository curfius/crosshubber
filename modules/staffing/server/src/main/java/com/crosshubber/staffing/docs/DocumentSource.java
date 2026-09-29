package com.crosshubber.staffing.docs;

/**
 * Module-owned document-source port (AI_MODULES_PLAN "Module-owned datasources"): the module
 * implements its own connectors and holds its own credentials — the portal hosts none. Adapter
 * selection is config-driven ({@code staffing.docsource.kind}); v1 ships {@code fake}, the
 * OneDrive/Graph adapter lands with Azure credentials.
 */
public interface DocumentSource {

  /** Fetched document content (text extracted when the format supports it). */
  record DocumentContent(String name, String mimeType, long size, String text) {}

  /**
   * Fetches a document by ref (format: adapter-specific, e.g. {@code onedrive:/folder/file.docx}).
   *
   * @throws Exception on transport/auth failure — callers convert to error payloads
   */
  DocumentContent fetchDocument(String documentRef) throws Exception;
}
