package com.crosshubber.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.crosshubber.portal.modules.navigation.pinnedapps.PinnedAppsService;

import tools.jackson.databind.ObjectMapper;

/**
 * Full-context smoke test against real PostgreSQL.
 *
 * <p>Verifies the things that break silently during platform upgrades: Flyway migrations apply, JPA
 * entities validate against the migrated schema, the reconciler seeds against Postgres (not H2),
 * and the public HTTP contract ({@code /healthz} 200 shape, unauthenticated API 401 JSON shape) is
 * preserved. Run before/after any dependency-platform change.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.default_schema=test",
      "spring.flyway.enabled=true",
      "spring.flyway.schemas=test",
      "portal.tenant-config-dir=target/test-classes/tenant-config"
    })
@ActiveProfiles("test")
@Testcontainers
class PortalSmokeTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    String url = POSTGRES.getJdbcUrl();
    String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=test";
    registry.add("spring.datasource.url", () -> schemaUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @LocalServerPort int port;

  @Autowired Flyway flyway;

  @Autowired PinnedAppsService pinnedAppsService;

  @Autowired ObjectMapper objectMapper;

  private RestClient http;

  @Autowired
  void initClient(RestClient.Builder builder) {
    this.http = builder.baseUrl("http://localhost:" + port).build();
  }

  @Test
  void allFlywayMigrationsApplied() {
    assertTrue(flyway.info().applied().length >= 18, "all V1..V18 migrations should be applied");
  }

  @Test
  void healthzReturnsUpWithContractShape() {
    ResponseEntity<String> response = http.get().uri("/healthz").retrieve().toEntity(String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"ok\":true"));
    assertTrue(response.getBody().contains("\"app\":\"portal\""));
    assertTrue(response.getBody().contains("\"db\":\"up\""));
  }

  @Test
  void unauthenticatedApiReturns401JsonContract() {
    ResponseEntity<String> response =
        http.get()
            .uri("/api/modules")
            .retrieve()
            .onStatus(s -> true, (r, res) -> {})
            .toEntity(String.class);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertTrue(response.getBody().contains("\"error\":\"unauthorized\""));
  }

  /**
   * Pinned-tree save is a delete+reinsert that HONORS client UUIDs — entities are persisted with
   * pre-assigned ids (Persistable/isNew). Guards against regressions of the Hibernate "detached
   * entity passed to persist" mapping bug.
   */
  @Test
  void pinnedTreeSaveHonorsClientUuids() {
    String userId = "smoke-pinned-user";
    String treeJson =
        """
        [
          {"id": "11111111-1111-1111-1111-111111111111", "nodeType": "folder", "name": "Work",
           "children": [
             {"id": "22222222-2222-2222-2222-222222222222", "nodeType": "item",
              "ref": "ai-hub:main", "children": []}
           ]}
        ]
        """;
    pinnedAppsService.savePinnedTree(userId, objectMapper.readTree(treeJson));

    List<Map<String, Object>> roots = pinnedAppsService.getPinnedTree(userId);
    assertEquals(1, roots.size());
    assertEquals("11111111-1111-1111-1111-111111111111", roots.get(0).get("id"));
    assertEquals("folder", roots.get(0).get("nodeType"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> children = (List<Map<String, Object>>) roots.get(0).get("children");
    assertEquals(1, children.size());
    assertEquals("22222222-2222-2222-2222-222222222222", children.get(0).get("id"));
    assertEquals("ai-hub:main", children.get(0).get("ref"));

    // Re-save with the same UUIDs (update path) must not throw either.
    pinnedAppsService.savePinnedTree(userId, objectMapper.readTree(treeJson));
    assertEquals(1, pinnedAppsService.getPinnedTree(userId).size());
  }
}
