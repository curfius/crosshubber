package com.crosshubber.portal.modules.msgcenter.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MsgCenterTemplateServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McTaskTemplateRepository templateRepo = mock(McTaskTemplateRepository.class);
  private final McTaskTemplateVersionRepository versionRepo =
      mock(McTaskTemplateVersionRepository.class);

  private MsgCenterTemplateService service;
  private McTaskTemplateEntity template;
  private McTaskTemplateVersionEntity v1;
  private McTaskTemplateVersionEntity v2;

  @BeforeEach
  void setUp() {
    service =
        new MsgCenterTemplateService(templateRepo, versionRepo, new JsonUtils(MAPPER), MAPPER);
    template = new McTaskTemplateEntity();
    setEntityId(template, 1L);
    template.setKey("expense-approval");
    template.setName("Expense approval");
    template.setCreatedAt(Instant.now());
    when(templateRepo.findByKey("expense-approval")).thenReturn(java.util.Optional.of(template));
    when(templateRepo.findAll()).thenReturn(List.of(template));

    v1 = version(1, "published");
    v2 = version(2, "published");
    when(versionRepo.findByTemplateIdOrderByVersionDesc(1L)).thenReturn(List.of(v2, v1));
    lenient()
        .when(versionRepo.findByTemplateIdAndVersion(1L, 1))
        .thenReturn(java.util.Optional.of(v1));
    lenient()
        .when(versionRepo.findByTemplateIdAndVersion(1L, 2))
        .thenReturn(java.util.Optional.of(v2));
    lenient().when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private McTaskTemplateVersionEntity version(int n, String status) {
    McTaskTemplateVersionEntity v = new McTaskTemplateVersionEntity();
    setEntityId(v, 100L + n);
    v.setTemplateId(1L);
    v.setVersion(n);
    v.setKind("approval");
    v.setCompletion("any");
    v.setFieldsJson("[{\"name\":\"amount\",\"required\":true,\"schema\":{\"type\":\"number\"}}]");
    v.setStatus(status);
    v.setCreatedAt(Instant.now());
    return v;
  }

  @Test
  void resolveExpandsPublishedVersionWithMarkers() {
    ObjectNode expanded = service.resolve("expense-approval", 2, MAPPER.createObjectNode());
    assertEquals("expense-approval", expanded.path("template").path("key").asString());
    assertEquals(2, expanded.path("template").path("version").asInt());
    assertEquals("approval", expanded.path("kind").asString());
    JsonNode fields = expanded.get("fields");
    assertTrue(fields != null && fields.isArray() && fields.size() == 1);
  }

  @Test
  void unknownKeyOrVersionIs422() {
    ResponseStatusException badKey =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolve("nope", 1, MAPPER.createObjectNode()));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badKey.getStatusCode());
    ResponseStatusException badVersion =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolve("expense-approval", 9, MAPPER.createObjectNode()));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badVersion.getStatusCode());
  }

  @Test
  void retiredVersionStillResolvesButOverridesRefused() {
    v2.setStatus("retired");
    ObjectNode noOverrides = MAPPER.createObjectNode();
    ObjectNode resolved = service.resolve("expense-approval", 2, noOverrides);
    assertEquals(2, resolved.path("template").path("version").asInt());
    ObjectNode withCompletion = MAPPER.createObjectNode();
    withCompletion.put("completion", "each");
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolve("expense-approval", 2, withCompletion));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
  }

  @Test
  void publishVersionStampsNextImmutableNumber() {
    when(versionRepo.findByTemplateIdAndVersion(1L, 3)).thenReturn(java.util.Optional.empty());
    JsonNode fields = MAPPER.readTree("[{\"name\":\"amount\",\"schema\":{\"type\":\"number\"}}]");
    var saved = service.publishVersion("expense-approval", "collect", "any", fields, null, "u1");
    assertEquals(3, saved.getVersion());
    assertEquals("published", saved.getStatus());
    assertTrue(saved.getFieldsJson().contains("amount"));
  }

  @Test
  void deleteBlockedWithPublishedVersions() {
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class, () -> service.deleteIfUnused("expense-approval", "u1"));
    assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
  }

  @Test
  void deleteAllowedWhenOnlyRetired() {
    v2.setStatus("retired");
    v1.setStatus("retired");
    service.deleteIfUnused("expense-approval", "u1");
    org.mockito.Mockito.verify(templateRepo).delete(template);
  }

  @Test
  void senderListingExposesOnlyPublished() {
    v2.setStatus("retired");
    var refs = service.listPublished();
    assertEquals(1, refs.size());
    assertEquals(1, refs.get(0).version());
  }

  private static void setEntityId(Object entity, long id) {
    try {
      var field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
