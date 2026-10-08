package com.crosshubber.portal.modules.msgcenter.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskDraftEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskDraftRepository;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskResponseRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MsgCenterTaskServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McMessageRepository messageRepo = mock(McMessageRepository.class);
  private final McTaskResponseRepository responseRepo = mock(McTaskResponseRepository.class);
  private final McTaskDraftRepository draftRepo = mock(McTaskDraftRepository.class);
  private final McTaskActivityRepository activityRepo = mock(McTaskActivityRepository.class);
  private final AllowlistSubmitValidator shape = new AllowlistSubmitValidator(MAPPER);

  private MsgCenterTaskService service;

  @BeforeEach
  void setUp() {
    service = new MsgCenterTaskService(messageRepo, responseRepo, draftRepo, activityRepo, shape);
    lenient().when(activityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(responseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(draftRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  /** Test fixture helper: generated ids are private — set them reflectively. */
  private static void setEntityId(Object entity, long id) {
    try {
      var field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private McMessageEntity claimTask(String status, String claimedBy) {
    McMessageEntity message = new McMessageEntity();
    setEntityId(message, 1L);
    message.setEventId("e1");
    message.setMsgType("task");
    message.setModuleKey("sample-sender");
    message.setAudienceJson(
        new java.util.LinkedHashMap<>(Map.of("users", List.of("u1", "u2"), "allUsers", false)));
    message.setTitleJson(Map.of("en", "t"));
    message.setBodyJson(Map.of("en", "b"));
    message.setTaskJson(
        new java.util.LinkedHashMap<>(
            Map.of(
                "kind", "collect",
                "completion", "any",
                "completionEvent", "loan.requested",
                "claim", Map.of("enabled", true, "mode", "single"),
                "fields",
                    List.of(
                        Map.of(
                            "name",
                            "days",
                            "required",
                            true,
                            "schema",
                            Map.of("type", "integer", "maximum", 30))))));
    message.setStatus(status);
    if (claimedBy != null) {
      message.setClaimedBySub(claimedBy);
      message.setClaimedByName("Claimer");
      message.setClaimedAt(Instant.now());
    }
    when(messageRepo.findById(1L)).thenReturn(Optional.of(message));
    return message;
  }

  @Test
  void claimCascadesOpenToClaimedAndRecordsActivity() {
    claimTask("open", null);
    var info = service.claim(1L, "u1", "Alice");
    assertEquals("u1", firstMessage().getClaimedBySub());
    assertEquals("claimed", firstMessage().getStatus());
    assertEquals("Alice", firstMessage().getClaimedByName());
    assertEquals(null, info.fromName()); // no predecessor draft yet
  }

  private McMessageEntity firstMessage() {
    return messageRepo.findById(1L).orElseThrow();
  }

  @Test
  void secondClaimLosesWith409() {
    claimTask("claimed", "u1");
    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.claim(1L, "u2", "Bob"));
    assertEquals(409, e.getStatusCode().value());
  }

  @Test
  void respondByNonClaimerForbidden() {
    claimTask("claimed", "u1");
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () -> service.respond(1L, "u2", "Bob", "submit", null, null, shape));
    assertEquals(403, e.getStatusCode().value());
  }

  @Test
  void claimModeSubmitClosesTaskAndReturnsClosed() {
    claimTask("claimed", "u1");
    JsonNode data = MAPPER.readTree("{\"days\": 3}");
    var result = service.respond(1L, "u1", "Alice", "submit", data, null, shape);
    assertTrue(result.closed());
    assertEquals("done", firstMessage().getStatus());
    assertEquals(
        3, ((java.math.BigDecimal) result.response().getDataJson().get("days")).intValue());
  }

  @Test
  void doneTasksAreTerminalForEverything() {
    claimTask("done", null);
    assertThrows(ResponseStatusException.class, () -> service.claim(1L, "u1", "Alice"));
    assertThrows(
        ResponseStatusException.class,
        () -> service.respond(1L, "u1", "Alice", "submit", null, null, shape));
    assertThrows(ResponseStatusException.class, () -> service.reset(1L, "u1", "Alice", true));
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class, () -> service.release(1L, "u1", "Alice", false, true));
    assertEquals(409, e.getStatusCode().value());
  }

  @Test
  void expiredTasksRejectClaimAndRespond() {
    McMessageEntity message = claimTask("open", null);
    message.getTaskJson().put("expiresAt", "2020-01-01T00:00:00Z");
    assertThrows(ResponseStatusException.class, () -> service.claim(1L, "u1", "Alice"));
    assertThrows(
        ResponseStatusException.class,
        () -> service.respond(1L, "u1", "Alice", "submit", null, null, shape));
  }

  @Test
  void releaseKeepsDraftForTakeoverAdopt() {
    claimTask("claimed", "u1");
    McTaskDraftEntity predecessor = new McTaskDraftEntity();
    predecessor.setMessageId(1L);
    predecessor.setUserSub("u1");
    predecessor.setUserName("Alice");
    predecessor.setDataJson(Map.of("days", 5));
    when(draftRepo.findByMessageId(1L)).thenReturn(List.of(predecessor));

    service.release(1L, "u1", "Alice", false, false);
    assertEquals("open", firstMessage().getStatus());

    // u2 takes over and adopts the draft
    service.claim(1L, "u2", "Bob");
    var offered = service.previousDraftOf(firstMessage(), "u2");
    assertEquals("Alice", offered.fromName());
    when(draftRepo.findByMessageIdAndUserSub(1L, "u2")).thenReturn(Optional.empty());
    Map<String, Object> adopted = service.adoptDraft(1L, "u2", "Bob");
    assertEquals(5.0, ((Number) adopted.get("days")).doubleValue());
  }

  @Test
  void resetClearsClaimAndDraftsButAuditSurvives() {
    claimTask("claimed", "u1");
    McTaskDraftEntity draft = new McTaskDraftEntity();
    draft.setMessageId(1L);
    draft.setUserSub("u1");
    when(draftRepo.findByMessageId(1L)).thenReturn(List.of(draft));

    service.reset(1L, "u1", "Alice", false);
    assertEquals("open", firstMessage().getStatus());
    assertEquals(null, firstMessage().getClaimedBySub());
    // audit rows appended (reset + draft_discard) — the repo saves were called
    org.mockito.Mockito.verify(activityRepo, org.mockito.Mockito.atLeast(2)).save(any());
  }

  @Test
  void eachModeClosesOnlyOnFinalResponse() {
    McMessageEntity message = claimTask("open", null);
    message.setTaskJson(
        Map.of(
            "kind",
            "collect",
            "completion",
            "each",
            "completionEvent",
            "x.y",
            "fields",
            List.of()));
    when(responseRepo.findByMessageIdAndUserSub(anyLong(), anyString()))
        .thenReturn(Optional.empty());
    when(responseRepo.countByMessageId(1L)).thenReturn(1L, 2L);

    var first = service.respond(1L, "u1", "Alice", "submit", null, null, shape);
    assertFalse(first.closed());
    assertEquals("open", firstMessage().getStatus());

    var second = service.respond(1L, "u2", "Bob", "submit", null, null, shape);
    assertTrue(second.closed());
    assertEquals("done", firstMessage().getStatus());
  }

  @Test
  void submitDataRuleEngineValidatesRequiredTypeBounds() {
    McMessageEntity message = claimTask("claimed", "u1");
    when(responseRepo.findByMessageIdAndUserSub(anyLong(), anyString()))
        .thenReturn(Optional.empty());

    // missing required field
    assertThrows(
        ResponseStatusException.class,
        () -> service.respond(1L, "u1", "Alice", "submit", MAPPER.readTree("{}"), null, shape));

    // over the maximum
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.respond(
                1L, "u1", "Alice", "submit", MAPPER.readTree("{\"days\": 44}"), null, shape));

    // wrong type (integer expected)
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.respond(
                1L,
                "u1",
                "Alice",
                "submit",
                MAPPER.readTree("{\"days\": \"three\"}"),
                null,
                shape));

    // valid
    var ok =
        service.respond(1L, "u1", "Alice", "submit", MAPPER.readTree("{\"days\": 3}"), null, shape);
    assertTrue(ok.closed());
  }

  @Test
  void unknownFieldsRejected() {
    claimTask("claimed", "u1");
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.respond(
                1L, "u1", "Alice", "submit", MAPPER.readTree("{\"nope\": 1}"), null, shape));
  }

  @Test
  void plainAnyTaskClosesOnFirstSubmitOnly() {
    McMessageEntity message = claimTask("open", null);
    message.getTaskJson().remove("claim");
    when(responseRepo.findByMessageIdAndUserSub(anyLong(), anyString()))
        .thenReturn(Optional.empty());

    var first =
        service.respond(1L, "u1", "Alice", "submit", MAPPER.readTree("{\"days\": 1}"), null, shape);
    assertTrue(first.closed());
    assertEquals("done", firstMessage().getStatus());

    // second submit races the done state → 409
    McMessageEntity done = firstMessage();
    when(responseRepo.findByMessageIdAndUserSub(1L, "u2")).thenReturn(Optional.empty());
    assertThrows(
        ResponseStatusException.class,
        () -> service.respond(done.getId(), "u2", "Bob", "submit", null, null, shape));
  }
}
