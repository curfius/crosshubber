package com.crosshubber.portal.modules.msgcenter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseEntity;
import com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService;
import com.crosshubber.portal.modules.msgcenter.publish.MsgCenterPublishService;
import com.crosshubber.portal.modules.msgcenter.tasks.AllowlistSubmitValidator;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterResponsePublisher;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterTaskService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MsgCenterAgentToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final PortalUser USER =
      new PortalUser("u1", "Alice", null, List.of("portal-user"));

  private final MsgCenterQueryService queryService = mock(MsgCenterQueryService.class);
  private final MsgCenterTaskService taskService = mock(MsgCenterTaskService.class);
  private final MsgCenterResponsePublisher responsePublisher =
      mock(MsgCenterResponsePublisher.class);
  private final MsgCenterPublishService publishService = mock(MsgCenterPublishService.class);

  private MsgCenterAgentTools tools;

  @BeforeEach
  void setUp() {
    tools =
        new MsgCenterAgentTools(
            queryService,
            taskService,
            responsePublisher,
            publishService,
            new AllowlistSubmitValidator(),
            MAPPER);
    when(publishService.publish(anyString(), any(JsonNode.class))).thenReturn(7L);
    when(queryService.unread(anyString(), any())).thenReturn(1L);
  }

  @Test
  void catalogueExposesFiveToolsWithCorrectMutability() {
    List<AgentTool> catalogue = tools.tools();
    assertEquals(5, catalogue.size());
    assertTrue(catalogue.stream().allMatch(t -> t.modelName().startsWith("msgcenter_")));
    // reads: no confirmation parking; writes: parked behind needsConfirmation
    for (String read : List.of("msgcenter_list", "msgcenter_get")) {
      assertFalse(byName(catalogue, read).mutates(), read + " must be a read tool");
    }
    for (String write :
        List.of("msgcenter_claim_task", "msgcenter_respond_task", "msgcenter_send")) {
      assertTrue(byName(catalogue, write).mutates(), write + " must be a mutating tool");
    }
  }

  private static AgentTool byName(List<AgentTool> catalogue, String modelName) {
    return catalogue.stream()
        .filter(t -> t.modelName().equals(modelName))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void listHonorsUnreadOnlyAndReportsUnread() {
    com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto read =
        item(1L, true, "notification");
    com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto unread =
        item(2L, false, "task");
    when(queryService.listOwn("u1", List.of("portal-user"), null, null, null, null, 20))
        .thenReturn(List.of(read, unread));
    when(queryService.unread("u1", List.of("portal-user"))).thenReturn(1L);

    JsonNode result =
        tools.handle(tool("msgcenter_list"), USER, MAPPER.readTree("{\"unreadOnly\": true}"));

    assertEquals(1, result.get("returned").asInt());
    assertEquals(2, result.get("items").get(0).get("id").asInt());
    assertEquals(1, result.get("unread").asInt());
  }

  @Test
  void getInvisibleItemIsANarratableError() {
    when(queryService.getOwn("u1", List.of("portal-user"), 9L)).thenReturn(null);
    JsonNode result = tools.handle(tool("msgcenter_get"), USER, MAPPER.readTree("{\"id\": 9}"));
    assertNotNull(result.get("error"));
  }

  @Test
  void claimGatedByAudienceVisibility() {
    when(queryService.visible("u1", List.of("portal-user"), 5L)).thenReturn(false);
    JsonNode result =
        tools.handle(tool("msgcenter_claim_task"), USER, MAPPER.readTree("{\"id\": 5}"));
    assertNotNull(result.get("error"));
    verify(queryService).visible("u1", List.of("portal-user"), 5L);
  }

  @Test
  void claimReturnsTakeoverOffer() {
    when(queryService.visible("u1", List.of("portal-user"), 5L)).thenReturn(true);
    when(taskService.claim(5L, "u1", "Alice"))
        .thenReturn(new MsgCenterTaskService.ClaimInfo("Bob", "t", Map.of("days", 2)));

    JsonNode result =
        tools.handle(tool("msgcenter_claim_task"), USER, MAPPER.readTree("{\"id\": 5}"));

    assertTrue(result.get("claimed").asBoolean());
    assertEquals("Bob", result.get("fromName").asString());
    assertEquals(2, result.get("draft").get("days").asInt());
  }

  @Test
  void respondRequiresOutcomeAndPublishesOnlyWhenClosed() {
    when(queryService.visible("u1", List.of("portal-user"), 5L)).thenReturn(true);
    McMessageEntity message = new McMessageEntity();
    setEntityId(message, 5L);
    message.setStatus("done");
    McTaskResponseEntity response = new McTaskResponseEntity();
    response.setOutcome("submit");
    when(taskService.respond(eq(5L), eq("u1"), eq("Alice"), eq("submit"), any(), any(), any()))
        .thenReturn(new MsgCenterTaskService.SubmitResult(true, message, response));

    JsonNode result =
        tools.handle(
            tool("msgcenter_respond_task"),
            USER,
            MAPPER.readTree("{\"id\": 5, \"outcome\": \"submit\", \"data\": {\"days\": 2}}"));

    assertTrue(result.get("closed").asBoolean());
    assertEquals("done", result.get("status").asString());
    verify(responsePublisher).publishResponse(message, response);
  }

  @Test
  void respondWithoutOutcomeFailsClean() {
    JsonNode result =
        tools.handle(tool("msgcenter_respond_task"), USER, MAPPER.readTree("{\"id\": 5}"));
    assertNotNull(result.get("error"));
  }

  @Test
  void sendRequiresEnvelopeObject() {
    JsonNode noEnvelope = tools.handle(tool("msgcenter_send"), USER, MAPPER.readTree("{}"));
    assertNotNull(noEnvelope.get("error"));
  }

  @Test
  void sendPublishesThroughTheSharedPath() {
    JsonNode result =
        tools.handle(
            tool("msgcenter_send"),
            USER,
            MAPPER.readTree("{\"envelope\": {\"v\": 1, \"type\": \"notification\"}}"));
    assertTrue(result.get("published").asBoolean());
    assertEquals(7, result.get("streamSeq").asInt());
  }

  private static AgentTool tool(String name) {
    return AgentTool.builtin(name, "desc", null, false, List.of());
  }

  private static com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto
      item(long id, boolean read, String type) {
    return new com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto(
        id,
        "e" + id,
        type,
        "portal-dashboard",
        null,
        null,
        Map.of("en", "t"),
        Map.of("en", "b"),
        "info",
        null,
        null,
        null,
        "open",
        null,
        null,
        null,
        java.time.Instant.parse("2026-10-06T10:00:00Z"),
        read,
        false,
        false);
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
