package com.crosshubber.portal.workspaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkspacesControllerTest {

  private final WorkspaceRepository repo = Mockito.mock(WorkspaceRepository.class);
  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final WorkspacesController controller =
      new WorkspacesController(repo, new JsonUtils(mapper));

  private static WorkspaceEntity row(String name) {
    WorkspaceEntity w = new WorkspaceEntity();
    w.setId(UUID.randomUUID());
    w.setUserId("u1");
    w.setName(name);
    w.setDescription("desc");
    w.setGroups("{}");
    w.setColor("#fff");
    w.setStatus("active");
    w.setSavedAt(Instant.ofEpochMilli(1234567890L));
    return w;
  }

  @Test
  void listUsesEpochMillisAndEmptyStringDefaults() {
    WorkspaceEntity w = row("Main");
    w.setDescription(null);
    w.setColor(null);
    w.setStatus(null);
    Mockito.when(repo.findByUserIdOrderBySavedAtDesc("u1")).thenReturn(List.of(w));

    var body = controller.list(authUser());

    // Workspaces list wraps typed WorkspaceSummaryDto records.
    com.crosshubber.portal.workspaces.dto.WorkspaceSummaryDto dto =
        (com.crosshubber.portal.workspaces.dto.WorkspaceSummaryDto)
            ((List<?>) body.get("workspaces")).get(0);
    assertEquals(1234567890L, dto.savedAt());
    assertEquals("", dto.description());
    assertEquals("", dto.color());
    assertEquals("", dto.status());
  }

  @Test
  void detailIncludesNullLayoutAndFocusedGroupId() {
    WorkspaceEntity w = row("Main");
    Mockito.when(repo.findByUserIdAndName("u1", "Main")).thenReturn(Optional.of(w));

    var response = controller.get(authUser(), "Main");

    com.crosshubber.portal.workspaces.dto.WorkspaceDetailDto dto =
        (com.crosshubber.portal.workspaces.dto.WorkspaceDetailDto) response.getBody();
    assertEquals("Main", dto.name());
    assertNull(dto.layout());
    JsonNode groups = dto.groups();
    assertTrue(groups.isObject());
    assertNull(dto.focusedGroupId());
    assertEquals(false, dto.hideSingleTabToolbar());
    assertEquals(false, dto.locked());
  }

  private static com.crosshubber.portal.security.PortalUser authUser() {
    return new com.crosshubber.portal.security.PortalUser("u1", "U", null, java.util.List.of());
  }
}
