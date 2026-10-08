package com.crosshubber.portal.modules.aihub.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;

/** AI plan A5: prompt assembler tests — stable ordering, catalogue section, budget behavior. */
class AgentPromptAssemblerTest {

  private AgentPromptAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new AgentPromptAssembler(new JsonUtils(new JacksonConfig().jsonMapper()));
  }

  private static SessionContextPack pack() {
    return new SessionContextPack(
        new SessionContextPack.User("Dev Admin", List.of("portal-admin")),
        new SessionContextPack.Tenant("dev", "en-GB"),
        new SessionContextPack.Location("ai-hub:main", "ai-hub", null),
        List.of(new SessionContextPack.OpenTab("ai-hub:main", "AI Hub")),
        "Ops",
        7);
  }

  @Test
  void buildsPersonaFirstThenPromptContextTools() {
    String prompt =
        assembler.build(
            "Be concise.",
            null,
            pack(),
            List.of(
                new AgentPromptAssembler.ToolSummary("getShellConfig", "Shell config", false),
                new AgentPromptAssembler.ToolSummary("update_stage", "Move project", true)));

    assertTrue(prompt.startsWith("You are the Crosshubber portal agent"), prompt);
    int personaEnd = prompt.indexOf("Be concise.");
    int promptEnd = prompt.indexOf("Session context:");
    int contextEnd = prompt.indexOf("Available tools:");
    assertTrue(personaEnd > 0 && promptEnd > personaEnd && contextEnd > promptEnd, prompt);
  }

  @Test
  void includesContextPackAsJson() {
    String prompt = assembler.build(null, null, pack(), List.of());

    assertTrue(prompt.contains("\"tenant\""), prompt);
    assertTrue(prompt.contains("\"slug\":\"dev\""), prompt);
    assertTrue(prompt.contains("\"contentVersion\":7"), prompt);
  }

  @Test
  void annotatesMutatingToolsInTheCatalogue() {
    String prompt =
        assembler.build(
            null,
            null,
            null,
            List.of(
                new AgentPromptAssembler.ToolSummary("read_tool", "Reads stuff", false),
                new AgentPromptAssembler.ToolSummary("write_tool", "Writes stuff", true)));

    assertTrue(prompt.contains("- read_tool — Reads stuff"), prompt);
    assertTrue(prompt.contains("- write_tool — Writes stuff (mutating)"), prompt);
    assertFalse(prompt.contains("(mutating)\n- read_tool"), prompt);
  }

  @Test
  void omitsEmptySections() {
    String prompt = assembler.build(null, null, null, List.of());

    assertEquals(
        "You are the Crosshubber portal agent, embedded in the Crosshubber portal. "
            + "You help the signed-in user with portal navigation, settings, and module data. "
            + "You may call tools to act on the user's behalf; mutating tools require the user's "
            + "explicit confirmation before they run. Answer in the user's language. Never invent "
            + "portal state you cannot see. When a document search tool returned results, mention "
            + "the document titles you relied on — the UI renders them as sources.",
        prompt);
  }

  @Test
  void capsToolCatalogueAtThirtyLines() {
    List<AgentPromptAssembler.ToolSummary> tools =
        java.util.stream.IntStream.range(0, 50)
            .mapToObj(i -> new AgentPromptAssembler.ToolSummary("tool_" + i, "Tool " + i, false))
            .toList();

    String prompt = assembler.build(null, null, null, tools);

    assertTrue(prompt.contains("tool_29"), prompt);
    assertFalse(prompt.contains("tool_30"), prompt);
  }

  @Test
  void includesUserContextSectionBetweenPromptAndPack() {
    String prompt = assembler.build("Be concise.", "I am a project manager.", pack(), List.of());

    int personaEnd = prompt.indexOf("Be concise.");
    int aboutStart = prompt.indexOf("User context:");
    int aboutEnd = prompt.indexOf("I am a project manager.");
    int packStart = prompt.indexOf("Session context:");
    assertTrue(aboutStart > personaEnd && aboutEnd > aboutStart && packStart > aboutEnd, prompt);
  }

  @Test
  void omitsBlankUserContext() {
    String prompt = assembler.build(null, "   ", pack(), List.of());

    assertFalse(prompt.contains("User context:"), prompt);
  }
}
