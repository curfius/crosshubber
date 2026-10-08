package com.crosshubber.portal.modules.navigation.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI plan D5: navigation tool declaration (module-attributed) and settings passthrough. */
class NavigationAgentToolsTest {

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  @Test
  void declaresModuleAttributedSettingsTool() {
    NavigationAgentTools tools =
        new NavigationAgentTools(
            mock(NavigationSettingsService.class), new JacksonConfig().jsonMapper());

    List<AgentTool> declared = tools.tools();

    assertThat(declared).hasSize(1);
    assertThat(declared.get(0).name()).isEqualTo("getInstanceSettings");
    assertThat(declared.get(0).moduleKey()).isEqualTo("navigation");
    assertThat(declared.get(0).kind()).isEqualTo(AgentTool.Kind.BUILTIN);
    assertThat(declared.get(0).mutates()).isFalse();
  }

  @Test
  void handleReturnsInstanceSettings() {
    NavigationSettingsService service = mock(NavigationSettingsService.class);
    Map<String, Object> settings = Map.of("homeApp", "portal-dashboard");
    when(service.get()).thenReturn(settings);
    ObjectMapper mapper = new JacksonConfig().jsonMapper();
    NavigationAgentTools tools = new NavigationAgentTools(service, mapper);

    JsonNode result = tools.handle(tools.tools().get(0), USER, mapper.createObjectNode());

    assertThat(result.path("homeApp").asString()).isEqualTo("portal-dashboard");
  }
}
