package com.crosshubber.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.crosshubber.portal.modules.aihub.providers.AiHubProviderEntity;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderRepository;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsService;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.ShellConfigService;
import com.crosshubber.portal.shell.dto.ShellConfigDto;

import tools.jackson.databind.JsonNode;

/**
 * Tenant-policy smoke test: boots against a policy-heavy fixture ({@code tenant-config-policy}) and
 * asserts the Reconciler applied every tenant-policy block — branding, i18n language set,
 * navigation settings (theme policy), builtin availability, and provider pre-enable. Complements
 * {@link PortalSmokeTest}, which covers the absent-block path with a minimal fixture.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.default_schema=policy",
      "spring.flyway.enabled=true",
      "spring.flyway.schemas=policy",
      "portal.tenant-config-dir=target/test-classes/tenant-config-policy"
    })
@ActiveProfiles("test")
@Testcontainers
class TenantPolicySmokeTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    String url = POSTGRES.getJdbcUrl();
    String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=policy";
    registry.add("spring.datasource.url", () -> schemaUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @LocalServerPort int port;

  @Autowired NavigationSettingsService navigationSettings;
  @Autowired ShellConfigService shellConfig;
  @Autowired ModulesService modulesService;
  @Autowired AiHubProviderRepository providerRepo;

  private RestClient http;

  @Autowired
  void initClient(RestClient.Builder builder) {
    this.http = builder.baseUrl("http://localhost:" + port).build();
  }

  @Test
  void brandingEndpointServesFixtureValues() {
    JsonNode branding = http.get().uri("/api/branding").retrieve().body(JsonNode.class);
    assertEquals("Policy Tenant", branding.get("name").asString());
    assertEquals("Policy Tenant Portal", branding.get("title").asString());
  }

  @Test
  void i18nConfigAssertsEnabledLanguageSet() {
    JsonNode config = http.get().uri("/api/i18n/config").retrieve().body(JsonNode.class);
    assertEquals("pt-PT", config.get("defaultLanguage").asString());
    Set<String> enabled =
        toStream(config.get("languages"))
            .filter(l -> l.get("enabled").asBoolean())
            .map(l -> l.get("code").asString())
            .collect(Collectors.toSet());
    assertEquals(Set.of("en-GB", "pt-PT"), enabled);
  }

  @Test
  void navigationSettingsCarryThemePolicy() {
    Map<String, Object> settings = navigationSettings.get();
    assertEquals("ocean", settings.get("defaultTheme"));
    assertEquals(List.of("ocean", "light", "nord"), settings.get("enabledThemes"));
    assertEquals("navigation:portal", settings.get("homeApp"));
  }

  @Test
  void shellConfigExcludesDisabledBuiltin() {
    ShellConfigDto config =
        shellConfig.buildConfig(new PortalUser("policy-user", "user", null, List.of()));
    Set<String> moduleKeys =
        config.moduleContents().stream().map(c -> c.moduleKey()).collect(Collectors.toSet());
    assertTrue(!moduleKeys.contains("sample-embedded"), "disabled builtin must be hidden");
    assertTrue(moduleKeys.contains("ai-hub"), "enabled builtins stay visible");
  }

  @Test
  void builtinActiveToggleIsRejectedWith409() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> modulesService.setActive("sample-embedded", false));
    assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
  }

  @Test
  void providerPreEnableApplied() {
    Map<String, Boolean> enabled = new HashMap<>();
    for (AiHubProviderEntity p : providerRepo.findAll()) {
      enabled.put(p.getId(), p.getEnabled());
    }
    assertEquals(Boolean.TRUE, enabled.get("anthropic"), "pre-enabled provider must be on");
    enabled.remove("anthropic");
    assertTrue(
        enabled.values().stream().noneMatch(Boolean.TRUE::equals),
        "non-listed providers stay disabled");
  }

  private static java.util.stream.Stream<JsonNode> toStream(JsonNode array) {
    java.util.List<JsonNode> out = new java.util.ArrayList<>();
    array.forEach(out::add);
    return out.stream();
  }
}
