package com.crosshubber.portal.modules.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** AI plan C4: audit endpoint response envelope ({@code {"toolCalls": [...]}}). */
class AgentToolCallsControllerTest {

  @Test
  void listRecentWrapsRowsInToolCallsEnvelope() {
    AgentToolCallService service = mock(AgentToolCallService.class);
    AgentToolCallDto row =
        new AgentToolCallDto(
            1L,
            null,
            "u1",
            null,
            "solutions",
            "solutions:list_projects",
            "list_projects",
            null,
            "ok",
            8);
    when(service.listRecent()).thenReturn(List.of(row));

    Map<String, List<AgentToolCallDto>> body = new AgentToolCallsController(service).listRecent();

    assertThat(body).containsOnlyKeys("toolCalls");
    assertThat(body.get("toolCalls")).containsExactly(row);
  }
}
