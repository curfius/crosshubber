package com.crosshubber.portal.modules.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

/** AI plan C4: audit list projection (entity -> DTO) preserving repository order. */
class AgentToolCallServiceTest {

  private static final AgentTool REMOTE_TOOL =
      AgentTool.remote(
          "solutions",
          "http://solutions:8090",
          "list_projects",
          "Lists projects",
          null,
          false,
          List.of("solutions-user"),
          null);

  @Test
  void listRecentMapsEntitiesToDtosPreservingRepositoryOrder() {
    AgentToolCallRepository repository = mock(AgentToolCallRepository.class);
    AgentToolCallEntity newest =
        AgentToolCallEntity.of("u1", "conv1", REMOTE_TOOL, "{\"health\":\"at-risk\"}", "ok", 12);
    AgentToolCallEntity older = AgentToolCallEntity.of("u2", null, REMOTE_TOOL, null, "denied", 0);
    when(repository.findTop200ByOrderByOccurredAtDesc()).thenReturn(List.of(newest, older));

    List<AgentToolCallDto> rows = new AgentToolCallService(repository).listRecent();

    assertThat(rows).hasSize(2);
    AgentToolCallDto first = rows.get(0);
    assertThat(first.userId()).isEqualTo("u1");
    assertThat(first.conversationId()).isEqualTo("conv1");
    assertThat(first.moduleKey()).isEqualTo("solutions");
    assertThat(first.toolId()).isEqualTo("solutions:list_projects");
    assertThat(first.toolName()).isEqualTo("list_projects");
    assertThat(first.argsSummary()).isEqualTo("{\"health\":\"at-risk\"}");
    assertThat(first.outcome()).isEqualTo("ok");
    assertThat(first.durationMs()).isEqualTo(12);

    AgentToolCallDto second = rows.get(1);
    assertThat(second.userId()).isEqualTo("u2");
    assertThat(second.conversationId()).isNull();
    assertThat(second.outcome()).isEqualTo("denied");
    assertThat(second.argsSummary()).isNull();
  }
}
