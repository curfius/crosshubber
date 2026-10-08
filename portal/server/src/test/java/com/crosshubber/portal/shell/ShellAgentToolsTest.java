package com.crosshubber.portal.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.dto.ShellConfigDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI plan D5: shell tool declaration (platform-global) and handler passthrough. */
class ShellAgentToolsTest {

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  @Test
  void declaresGlobalShellConfigTool() {
    ShellConfigService service = mock(ShellConfigService.class);
    ShellAgentTools tools = new ShellAgentTools(service, new JacksonConfig().jsonMapper());

    List<AgentTool> declared = tools.tools();

    assertThat(declared).hasSize(1);
    assertThat(declared.get(0).name()).isEqualTo("getShellConfig");
    assertThat(declared.get(0).moduleKey()).isNull();
    assertThat(declared.get(0).kind()).isEqualTo(AgentTool.Kind.BUILTIN);
    assertThat(declared.get(0).mutates()).isFalse();
  }

  @Test
  void handleReturnsShellConfigForCaller() {
    ShellConfigService service = mock(ShellConfigService.class);
    ObjectMapper mapper = new JacksonConfig().jsonMapper();
    ShellConfigDto dto = new ShellConfigDto(null, Map.of(), List.of(), List.of(), List.of());
    when(service.buildConfig(USER)).thenReturn(dto);
    ShellAgentTools tools = new ShellAgentTools(service, mapper);

    JsonNode result = tools.handle(tools.tools().get(0), USER, mapper.createObjectNode());

    assertThat(result).isEqualTo(mapper.valueToTree(dto));
  }
}
