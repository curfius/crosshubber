package com.crosshubber.portal.modules.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** AI plan D5: i18n label tool declaration (platform-global) and prefix/limit handling. */
class I18nAgentToolsTest {

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  private I18nService i18nService;
  private I18nAgentTools tools;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    i18nService = mock(I18nService.class);
    mapper = new JacksonConfig().jsonMapper();
    tools = new I18nAgentTools(i18nService, mapper);
    I18nSettingsEntity settings = new I18nSettingsEntity();
    settings.setDefaultLanguage("en-GB");
    when(i18nService.getSettingsRow()).thenReturn(settings);
    when(i18nService.getLabels("en-GB"))
        .thenReturn(
            Map.of(
                "registry.title", "Registry",
                "nav.home", "Home",
                "other.key", "Other"));
  }

  @Test
  void declaresGlobalLabelToolWithSchema() {
    List<AgentTool> declared = tools.tools();

    assertThat(declared).hasSize(1);
    AgentTool tool = declared.get(0);
    assertThat(tool.name()).isEqualTo("getI18nLabels");
    assertThat(tool.moduleKey()).isNull();
    assertThat(tool.kind()).isEqualTo(AgentTool.Kind.BUILTIN);
    JsonNode properties = tool.arguments().path("properties");
    assertThat(properties.has("language")).isTrue();
    assertThat(properties.has("prefix")).isTrue();
    assertThat(properties.has("limit")).isTrue();
  }

  @Test
  void handleDefaultsLanguageAndFiltersByPrefix() {
    ObjectNode args = mapper.createObjectNode();
    args.put("prefix", "registry.");

    JsonNode result = tools.handle(tools.tools().get(0), USER, args);

    assertThat(result.path("language").asString()).isEqualTo("en-GB");
    assertThat(result.path("total").asInt()).isEqualTo(1);
    assertThat(result.path("returned").asInt()).isEqualTo(1);
    assertThat(result.path("labels").get(0).path("key").asString()).isEqualTo("registry.title");
  }

  @Test
  void handleWithoutPrefixReturnsAllLabels() {
    JsonNode result = tools.handle(tools.tools().get(0), USER, mapper.createObjectNode());

    assertThat(result.path("total").asInt()).isEqualTo(3);
    assertThat(result.path("returned").asInt()).isEqualTo(3);
  }
}
