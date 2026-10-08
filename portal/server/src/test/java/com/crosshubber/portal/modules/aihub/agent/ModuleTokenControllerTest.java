package com.crosshubber.portal.modules.aihub.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;

/** Module-token bridge: issues only for active modules with a base url (P5). */
class ModuleTokenControllerTest {

  private ModulesService modulesService;
  private AgentCallAuthorizer authorizer;
  private ModuleTokenController controller;

  private static final PortalUser USER =
      new PortalUser("u1", "Dev Admin", "d@x", List.of("portal-admin"));

  @BeforeEach
  void setUp() {
    modulesService = mock(ModulesService.class);
    PortalProperties props = new PortalProperties();
    props.setSessionSecret("test-secret");
    authorizer = new AgentCallAuthorizer(props, new JsonUtils(new JacksonConfig().jsonMapper()));
    controller = new ModuleTokenController(authorizer, modulesService);
  }

  private static ModuleEntity module(String key, boolean active, String baseUrl) {
    ModuleEntity m = new ModuleEntity();
    m.setKey(key);
    m.setActive(active);
    m.setBaseUrl(baseUrl);
    return m;
  }

  @Test
  void issuesTokenForActiveModuleWithBaseUrl() {
    when(modulesService.list(false))
        .thenReturn(List.of(module("solutions", true, "http://solutions:8090")));

    ResponseEntity<?> response = controller.issue(USER, "solutions");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertNotNull(body);
    assertNotNull(body.get("token"));
    assertEquals(300L, body.get("expiresIn"));
    assertNotNull(authorizer.verify((String) body.get("token")));
  }

  @Test
  void failsClosedForUnknownModule() {
    when(modulesService.list(false)).thenReturn(List.of());

    ResponseEntity<?> response = controller.issue(USER, "nope");

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void failsClosedForInactiveModule() {
    when(modulesService.list(false))
        .thenReturn(List.of(module("solutions", false, "http://solutions:8090")));

    assertEquals(HttpStatus.NOT_FOUND, controller.issue(USER, "solutions").getStatusCode());
  }

  @Test
  void failsClosedForModuleWithoutBaseUrl() {
    when(modulesService.list(false)).thenReturn(List.of(module("solutions", true, null)));

    assertEquals(HttpStatus.NOT_FOUND, controller.issue(USER, "solutions").getStatusCode());
  }
}
