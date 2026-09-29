package com.crosshubber.portal.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** AI plan B7: dispatcher RBAC matrix, confirmation gating, iteration cap, audit outcomes. */
class ToolDispatcherTest {

  private BuiltinToolHandlers builtinHandlers;
  private RemoteToolInvoker remoteInvoker;
  private AgentToolCallRepository auditRepository;
  private ToolDispatcher dispatcher;
  private ObjectMapper mapper;

  private static final PortalUser USER =
      new PortalUser("u1", "Dev", "d@x", List.of("solutions-user"));

  @BeforeEach
  void setUp() {
    builtinHandlers = mock(BuiltinToolHandlers.class);
    remoteInvoker = mock(RemoteToolInvoker.class);
    auditRepository = mock(AgentToolCallRepository.class);
    mapper = new JacksonConfig().jsonMapper();
    dispatcher =
        new ToolDispatcher(
            builtinHandlers, remoteInvoker, new PendingToolCallStore(), auditRepository, mapper);
  }

  private static AgentTool readTool() {
    return AgentTool.builtin("read_tool", "reads", null, false, List.of());
  }

  private static AgentTool writeTool(String... roles) {
    return AgentTool.builtin("write_tool", "writes", null, true, List.of(roles));
  }

  private static AgentTool remoteTool(String... roles) {
    return AgentTool.remote(
        "solutions",
        "http://solutions:8090",
        "get_project",
        "Gets a project",
        null,
        false,
        List.of(roles),
        null);
  }

  @Test
  void builtinReadDispatchesAndAuditsOk() {
    ObjectNode payload = mapper.createObjectNode().put("value", 42);
    when(builtinHandlers.handle(any(), any(), any())).thenReturn(payload);

    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(readTool(), USER, "conv1", mapper.createObjectNode(), false);

    assertThat(result.status()).isEqualTo("ok");
    assertThat(result.payload().path("value").asInt()).isEqualTo(42);
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getOutcome()).isEqualTo("ok");
    assertThat(audit.getValue().getUserId()).isEqualTo("u1");
    assertThat(audit.getValue().getConversationId()).isEqualTo("conv1");
  }

  @Test
  void deniesCallersWithoutRequiredRole() {
    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(
            remoteTool("solutions-admin"), USER, null, mapper.createObjectNode(), false);

    assertThat(result.status()).isEqualTo("denied");
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getOutcome()).isEqualTo("denied");
  }

  @Test
  void allowsCallersWithAnyRequiredRole() throws Exception {
    AgentTool tool = remoteTool("solutions-admin", "solutions-user");
    when(remoteInvoker.invoke(any(), any(), any()))
        .thenReturn(mapper.createObjectNode().put("ok", true));

    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(tool, USER, null, mapper.createObjectNode(), false);

    assertThat(result.status()).isEqualTo("ok");
  }

  @Test
  void mutatingToolRequiresConfirmationBeforeExecuting() {
    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(writeTool(), USER, "conv1", mapper.createObjectNode(), false);

    assertThat(result.status()).isEqualTo("needs_confirmation");
    assertThat(result.callId()).isNotBlank();
    assertThat(result.payload().path("status").asString()).isEqualTo("needs_confirmation");
    org.mockito.Mockito.verifyNoInteractions(builtinHandlers);
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getOutcome()).isEqualTo("needs_confirmation");
  }

  @Test
  void confirmedMutatingToolExecutesAndAuditsConfirmed() {
    ObjectNode payload = mapper.createObjectNode().put("done", true);
    when(builtinHandlers.handle(any(), any(), any())).thenReturn(payload);

    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(writeTool(), USER, "conv1", mapper.createObjectNode(), true);

    assertThat(result.status()).isEqualTo("ok");
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getOutcome()).isEqualTo("confirmed");
  }

  @Test
  void remoteTransportFailureIsErrorNotException() throws Exception {
    when(remoteInvoker.invoke(any(), any(), any()))
        .thenThrow(new java.net.ConnectException("down"));

    ToolDispatcher.ToolResult result =
        dispatcher.dispatch(remoteTool(), USER, null, mapper.createObjectNode(), false);

    assertThat(result.status()).isEqualTo("error");
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getOutcome()).isEqualTo("error");
  }

  @Test
  void executePendingRunsOwnedCallAndRejectsOthers() {
    // create pending from the mutating dispatch path
    ToolDispatcher.ToolResult offered =
        dispatcher.dispatch(writeTool(), USER, "conv1", mapper.createObjectNode(), false);

    // wrong user cannot confirm
    PortalUser other = new PortalUser("u2", "Other", "o@x", List.of());
    ToolDispatcher.ToolResult stolen = dispatcher.executePending(offered.callId(), other);
    assertThat(stolen.status()).isEqualTo("error");

    // owner confirms → executes
    when(builtinHandlers.handle(any(), any(), any()))
        .thenReturn(mapper.createObjectNode().put("moved", true));
    ToolDispatcher.ToolResult done = dispatcher.executePending(offered.callId(), USER);
    assertThat(done.status()).isEqualTo("ok");
    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository, org.mockito.Mockito.atLeastOnce())
        .save(audit.capture());
    assertThat(audit.getAllValues())
        .anySatisfy(e -> assertThat(e.getOutcome()).isEqualTo("confirmed"));
  }

  @Test
  void executePendingFailsClosedOnUnknownCallId() {
    ToolDispatcher.ToolResult result = dispatcher.executePending("call_missing", USER);
    assertThat(result.status()).isEqualTo("error");
  }

  @Test
  void auditsArgsSummaryTruncated() {
    when(builtinHandlers.handle(any(), any(), any())).thenReturn(mapper.createObjectNode());
    ObjectNode args = mapper.createObjectNode().put("blob", "y".repeat(2000));

    dispatcher.dispatch(readTool(), USER, null, args, false);

    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getArgsSummary().length()).isLessThanOrEqualTo(512);
  }

  @Test
  void dispatchPassesToolIdentityToAudit() {
    when(builtinHandlers.handle(any(), any(), any())).thenReturn(mapper.createObjectNode());

    dispatcher.dispatch(readTool(), USER, "conv9", mapper.createObjectNode(), false);

    ArgumentCaptor<AgentToolCallEntity> audit = ArgumentCaptor.forClass(AgentToolCallEntity.class);
    org.mockito.Mockito.verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getToolId()).isEqualTo("read_tool");
    assertThat(audit.getValue().getToolName()).isEqualTo("read_tool");
    assertThat(audit.getValue().getModuleKey()).isNull();
  }
}
