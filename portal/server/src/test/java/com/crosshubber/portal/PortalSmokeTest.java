package com.crosshubber.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
}
