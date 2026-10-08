package com.crosshubber.portal.workspaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI plan D5: workspace tool declaration (platform-global) and caller-scoped handler. */
class WorkspacesAgentToolsTest {

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  private WorkspaceEntity workspace(String name, Instant savedAt) {
    WorkspaceEntity w = new WorkspaceEntity();
    w.setName(name);
    w.setDescription(name + " desc");
    w.setStatus("active");
    w.setSavedAt(savedAt);
    return w;
  }

  @Test
  void declaresGlobalWorkspacesTool() {
    WorkspacesAgentTools tools =
        new WorkspacesAgentTools(mock(WorkspaceRepository.class), new JacksonConfig().jsonMapper());

    List<AgentTool> declared = tools.tools();

    assertThat(declared).hasSize(1);
    assertThat(declared.get(0).name()).isEqualTo("listWorkspaces");
    assertThat(declared.get(0).moduleKey()).isNull();
    assertThat(declared.get(0).kind()).isEqualTo(AgentTool.Kind.BUILTIN);
  }

  @Test
  void handleReturnsCallerWorkspacesCappedAtFifty() {
    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    List<WorkspaceEntity> rows = new ArrayList<>();
    for (int i = 0; i < 55; i++) {
      rows.add(workspace("ws-" + i, Instant.parse("2026-10-08T10:00:00Z")));
    }
    when(repository.findByUserIdOrderBySavedAtDesc(USER.sub())).thenReturn(rows);
    ObjectMapper mapper = new JacksonConfig().jsonMapper();
    WorkspacesAgentTools tools = new WorkspacesAgentTools(repository, mapper);

    JsonNode result = tools.handle(tools.tools().get(0), USER, mapper.createObjectNode());

    assertThat(result).hasSize(50);
    assertThat(result.get(0).path("name").asString()).isEqualTo("ws-0");
    assertThat(result.get(0).path("savedAt").asString()).isEqualTo("2026-10-08T10:00:00Z");
  }
}
