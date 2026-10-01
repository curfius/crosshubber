package com.crosshubber.portal.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.agent.CitationExtractor.Citation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI plan G3: citation extraction from successful tool-result payloads. */
class CitationExtractorTest {

  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();

  private JsonNode payload(String json) throws Exception {
    return mapper.readTree(json);
  }

  @Test
  void extractsFromSnippetsArrayWithDocumentRef() throws Exception {
    JsonNode payload =
        payload(
            """
            {"snippets":[
              {"documentRef":"fake:proposal-scope","title":"Proposal - scope",
               "snippet":"fixed 180k, 24 weeks","score":3}
            ]}
            """);

    List<Citation> citations = CitationExtractor.fromPayload(payload);

    assertThat(citations).hasSize(1);
    assertThat(citations.get(0).title()).isEqualTo("Proposal - scope");
    assertThat(citations.get(0).ref()).isEqualTo("fake:proposal-scope");
    assertThat(citations.get(0).snippet()).isEqualTo("fixed 180k, 24 weeks");
  }

  @Test
  void prefersCitationsFieldOverSnippets() throws Exception {
    JsonNode payload =
        payload(
            """
            {"citations":[{"title":"A","ref":"a"}],"snippets":[{"title":"B","snippet":"b"}]}
            """);

    List<Citation> citations = CitationExtractor.fromPayload(payload);

    assertThat(citations).hasSize(1);
    assertThat(citations.get(0).title()).isEqualTo("A");
  }

  @Test
  void skipsItemsWithoutTitleAndBlanks() throws Exception {
    JsonNode payload =
        payload(
            """
            {"snippets":[{"documentRef":"x"},{"title":""},{"title":"  "},{"title":"Kept"}]}
            """);

    List<Citation> citations = CitationExtractor.fromPayload(payload);

    assertThat(citations).hasSize(1);
    assertThat(citations.get(0).title()).isEqualTo("Kept");
    assertThat(citations.get(0).ref()).isNull();
    assertThat(citations.get(0).snippet()).isNull();
  }

  @Test
  void capsAtFiveAndTruncatesLongSnippets() throws Exception {
    StringBuilder json = new StringBuilder("{\"snippets\":[");
    for (int i = 0; i < 8; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"title\":\"d").append(i).append("\",\"snippet\":\"s\"}");
    }
    json.append("]}");
    JsonNode withCap = payload(json.toString());
    assertThat(CitationExtractor.fromPayload(withCap)).hasSize(CitationExtractor.MAX_CITATIONS);

    JsonNode longSnippet =
        payload(
            "{\"snippets\":[{" + "\"title\":\"t\"," + "\"snippet\":\"" + "x".repeat(250) + "\"}]}");
    Citation truncated = CitationExtractor.fromPayload(longSnippet).get(0);
    assertThat(truncated.snippet()).hasSize(CitationExtractor.MAX_SNIPPET_CHARS + 3);
    assertThat(truncated.snippet()).endsWith("...");
  }

  @Test
  void ignoresPayloadsWithoutCitationShape() throws Exception {
    assertThat(CitationExtractor.fromPayload(payload("{\"projects\":[]}"))).isEmpty();
    assertThat(CitationExtractor.fromPayload(payload("{\"snippets\":\"nope\"}"))).isEmpty();
    assertThat(CitationExtractor.fromPayload(null)).isEmpty();
  }
}
