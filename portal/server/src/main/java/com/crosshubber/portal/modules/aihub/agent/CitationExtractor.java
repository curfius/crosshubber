package com.crosshubber.portal.modules.aihub.agent;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Extracts document citations from a successful tool-result payload (AI plan G3): tools that answer
 * with a {@code citations[]} or {@code snippets[]} array of objects carrying at least a {@code
 * title} cause a {@code citation} frame next to the {@code tool_result} — the UI renders the
 * sources under the tool row. Shape-agnostic about which module produced the payload; the solutions
 * {@code search_project_docs} tool ({@code snippets[]} with {@code documentRef}) is the first
 * consumer.
 */
final class CitationExtractor {

  static final int MAX_CITATIONS = 5;
  static final int MAX_SNIPPET_CHARS = 200;

  /** One rendered source: document title plus optional ref and truncated snippet. */
  record Citation(String title, String ref, String snippet) {}

  private CitationExtractor() {}

  /**
   * Pulls citations out of a tool-result payload. Looks at {@code citations[]} first, then {@code
   * snippets[]}; items without a non-blank {@code title} are skipped. Returns an empty list for
   * payloads without a citation-shaped array.
   */
  static List<Citation> fromPayload(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      return List.of();
    }
    for (String field : List.of("citations", "snippets")) {
      List<Citation> citations = extract(payload.get(field));
      if (!citations.isEmpty()) {
        return citations;
      }
    }
    return List.of();
  }

  private static List<Citation> extract(JsonNode array) {
    if (array == null || !array.isArray()) {
      return List.of();
    }
    List<Citation> citations = new ArrayList<>();
    for (JsonNode item : array) {
      if (citations.size() >= MAX_CITATIONS) {
        break;
      }
      String title = text(item, "title");
      if (title == null) {
        continue;
      }
      citations.add(
          new Citation(
              title, firstText(item, "ref", "documentRef"), truncate(text(item, "snippet"))));
    }
    return citations;
  }

  private static String firstText(JsonNode item, String... fields) {
    for (String field : fields) {
      String value = text(item, field);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String text(JsonNode item, String field) {
    JsonNode value = item.path(field);
    return value.isString() && !value.asString().isBlank() ? value.asString() : null;
  }

  private static String truncate(String snippet) {
    if (snippet == null || snippet.length() <= MAX_SNIPPET_CHARS) {
      return snippet;
    }
    return snippet.substring(0, MAX_SNIPPET_CHARS) + "...";
  }
}
