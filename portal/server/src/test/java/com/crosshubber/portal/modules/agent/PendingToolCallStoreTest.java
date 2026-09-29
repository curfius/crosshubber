package com.crosshubber.portal.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.ObjectMapper;

/** Pending mutating-call store: ownership, expiry, capacity. */
class PendingToolCallStoreTest {

  private static final PortalUser USER =
      new PortalUser("u1", "Dev", "d@x", List.of("solutions-user"));

  private final PendingToolCallStore store = new PendingToolCallStore();
  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();

  @Test
  void takeReturnsAndRemovesOwnedCall() {
    String callId = store.create(writeTool(), USER, "conv1", mapper.createObjectNode());

    PendingToolCallStore.PendingCall call = store.take(callId, "u1");

    assertThat(call).isNotNull();
    assertThat(call.tool().name()).isEqualTo("write_tool");
    assertThat(call.conversationId()).isEqualTo("conv1");
    assertThat(store.take(callId, "u1")).as("removed after take").isNull();
  }

  @Test
  void takeRejectsWrongOwner() {
    String callId = store.create(writeTool(), USER, null, mapper.createObjectNode());

    assertThat(store.take(callId, "u2")).isNull();
    assertThat(store.take(callId, "u1")).as("still available for owner").isNotNull();
  }

  @Test
  void takeRejectsUnknownIds() {
    assertThat(store.take(null, "u1")).isNull();
    assertThat(store.take("call_missing", "u1")).isNull();
  }

  @Test
  void rejectsCreationWhenFull() {
    PendingToolCallStore tiny = new PendingToolCallStore();
    String last = null;
    for (int i = 0; i < 100; i++) {
      last = tiny.create(writeTool(), USER, null, mapper.createObjectNode());
    }
    assertThat(last).isNotNull();
    assertThat(tiny.create(writeTool(), USER, null, mapper.createObjectNode())).isNull();
  }

  private AgentTool writeTool() {
    return AgentTool.builtin("write_tool", "writes", null, true, List.of());
  }
}
