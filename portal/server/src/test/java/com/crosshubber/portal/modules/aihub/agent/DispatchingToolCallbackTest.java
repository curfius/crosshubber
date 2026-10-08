package com.crosshubber.portal.modules.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI plan G3: the tool-calling adapter emits one citation frame after a successful result that
 * carries a snippets/citations array — and nothing for denied/error results.
 */
class DispatchingToolCallbackTest {

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  private ToolDispatcher dispatcher;
  private ObjectMapper mapper;
  private List<String> emitted;
  private DispatchingToolCallback callback;

  @BeforeEach
  void setUp() {
    dispatcher = mock(ToolDispatcher.class);
    mapper = new JacksonConfig().jsonMapper();
    emitted = new java.util.ArrayList<>();
    AgentTool tool =
        AgentTool.remote(
            "solutions",
            "http://solutions:8090",
            "search_docs",
            "searches",
            null,
            false,
            List.of(),
            null);
    callback =
        new DispatchingToolCallback(
            tool, dispatcher, mapper, USER, "c1", emitted::add, 8, new AtomicInteger());
  }

  private JsonNode payload(String json) throws Exception {
    return mapper.readTree(json);
  }

  @Test
  void successfulSearchEmitsCitationFrameAfterToolResult() throws Exception {
    JsonNode snippets =
        payload(
            """
            {"snippets":[{"documentRef":"fake:proposal-scope","title":"Proposal",
                          "snippet":"fixed 180k"}]}
            """);
    when(dispatcher.dispatch(any(), any(), eq("c1"), any(), anyBoolean()))
        .thenReturn(new ToolDispatcher.ToolResult("ok", snippets, null));

    callback.call("{\"query\":\"pricing\"}");

    assertThat(emitted).hasSize(3); // tool_call, tool_result, citation
    assertThat(emitted.get(0)).contains("\"type\":\"tool_call\"");
    assertThat(emitted.get(1)).contains("\"type\":\"tool_result\"");
    assertThat(emitted.get(2)).contains("\"type\":\"citation\"");
    JsonNode toolCall = mapper.readTree(emitted.get(0));
    assertThat(toolCall.path("tool").asString()).isEqualTo("solutions_search_docs");
    assertThat(toolCall.path("module").asString()).isEqualTo("solutions");
    assertThat(toolCall.path("kind").asString()).isEqualTo("remote");
    JsonNode citation = mapper.readTree(emitted.get(2));
    assertThat(citation.path("tool").asString()).isEqualTo("solutions_search_docs");
    assertThat(citation.path("citations").size()).isEqualTo(1);
    assertThat(citation.path("citations").get(0).path("title").asString()).isEqualTo("Proposal");
  }

  @Test
  void deniedResultEmitsNoCitationFrame() throws Exception {
    when(dispatcher.dispatch(any(), any(), eq("c1"), any(), anyBoolean()))
        .thenReturn(
            new ToolDispatcher.ToolResult("denied", payload("{\"error\":\"denied\"}"), null));

    callback.call("{}");

    assertThat(emitted).hasSize(2); // tool_call, tool_result — no citation
    assertThat(emitted.get(0)).contains("\"type\":\"tool_call\"");
    assertThat(emitted.get(1)).contains("\"type\":\"tool_result\"");
  }
}
