package com.crosshubber.portal.modules.registry.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manifest schema tests — focus on {@code agentContributions} v2 (AI plan D2): tools/skills/
 * agents/knowledge shape validation plus v1 backwards compatibility (empty arrays normalize).
 */
class ManifestValidatorTest {

  private ManifestValidator validator;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    PortalProperties props = new PortalProperties();
    props.setPublicBaseUrl("http://localhost:28084");
    mapper = new JacksonConfig().jsonMapper();
    validator = new ManifestValidator(props, (JsonMapper) mapper);
  }

  private JsonNode manifest(String agentJson) {
    String base =
        """
        {"manifestVersion":1,"key":"test-module","name":"Test Module",
         "baseUrl":"http://test:8080",
         "content":{"applications":[{"key":"main","name":"Main","type":"iframe","url":"http://test:8080/app"}]}}
        """;
    JsonNode node = mapper.readTree(base);
    if (agentJson != null) {
      ((tools.jackson.databind.node.ObjectNode) node)
          .set("agentContributions", mapper.readTree(agentJson));
    }
    return node;
  }

  // --- v1 backwards compatibility ---

  @Test
  void parseDefaultsAllAgentArraysWhenContributionsAbsent() {
    ManifestValidator.Result result = validator.parse(manifest(null));

    assertTrue(result.ok());
    JsonNode agent = result.manifest().path("agentContributions");
    assertTrue(agent.path("tools").isArray());
    assertTrue(agent.path("tools").isEmpty());
    assertTrue(agent.path("skills").isArray());
    assertTrue(agent.path("agents").isArray());
    assertTrue(agent.path("knowledge").isArray());
  }

  @Test
  void parseNormalizesV1ShapeWithOnlyToolsAndSkills() {
    ManifestValidator.Result result = validator.parse(manifest("{\"tools\":[],\"skills\":[]}"));

    assertTrue(result.ok());
    JsonNode agent = result.manifest().path("agentContributions");
    assertTrue(agent.path("agents").isArray());
    assertTrue(agent.path("knowledge").isArray());
  }

  // --- tools[] v2 ---

  @Test
  void parseAcceptsValidV2Tools() {
    String agent =
        """
        {"tools":[
          {"name":"list_projects","description":"List projects","mutates":false,
           "roles":["solutions-user"],
           "arguments":{"type":"object","properties":{"stage":{"type":"string"}}}},
           {"name":"update_stage","description":"Move a project",
           "mutates":true,"path":"/agent/tools/move"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertTrue(result.ok(), "issues: " + result.issues());
    assertEquals(2, result.manifest().path("agentContributions").path("tools").size());
  }

  @Test
  void parseRejectsInvalidToolNamesAndDuplicates() {
    String agent =
        """
        {"tools":[
          {"name":"Bad_Name","description":"x"},
          {"name":"dup_tool","description":"x"},
          {"name":"dup_tool","description":"y"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertFalse(result.ok());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("tools[0].name: must match")),
        "issues: " + result.issues());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("duplicate tool name")),
        "issues: " + result.issues());
  }

  @Test
  void parseRejectsToolsWithMissingDescriptionBadArgsRolesPath() {
    String agent =
        """
        {"tools":[
          {"name":"broken_tool",
           "arguments":"not-an-object",
           "mutates":"yes",
           "roles":["Bad Role"],
           "path":"agent/tools/x"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertFalse(result.ok());
    List<String> issues = result.issues();
    assertTrue(
        issues.stream().anyMatch(i -> i.contains(".description: required")), issues.toString());
    assertTrue(
        issues.stream().anyMatch(i -> i.contains(".arguments: must be an object")),
        issues.toString());
    assertTrue(
        issues.stream().anyMatch(i -> i.contains(".mutates: must be a boolean")),
        issues.toString());
    assertTrue(
        issues.stream().anyMatch(i -> i.contains(".roles[0]: must match")), issues.toString());
    assertTrue(
        issues.stream().anyMatch(i -> i.contains(".path: must start with")), issues.toString());
  }

  @Test
  void parseAcceptsSnakeCaseToolNames() {
    String agent =
        """
        {"tools":[
          {"name":"list_projects","description":"List projects"},
          {"name":"get-rfp","description":"Kebab also works"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertTrue(result.ok(), "issues: " + result.issues());
  }

  // --- skills[] v2 ---

  @Test
  void parseValidatesSkills() {
    String agent =
        """
        {"skills":[
          {"name":"proposal-writer","description":"Draft proposals","prompts":["Write a proposal"]},
          {"name":"referenced","description":"Uses a ref","promptRef":"cat://skills/1"},
          {"name":"bad-skill","description":"No prompts"},
          {"name":"bad2","description":"Empty prompts","prompts":[]},
          {"name":"bad3","description":"Non-string","prompts":[42]}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertFalse(result.ok());
    assertTrue(
        result.issues().stream().noneMatch(i -> i.contains("skills[0]"))
            && result.issues().stream().noneMatch(i -> i.contains("skills[1]")),
        "valid skills must pass: " + result.issues());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("skills[3].prompts")), issues(result));
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("skills[4].prompts")), issues(result));
  }

  // --- agents[] (Phase H) ---

  @Test
  void parseValidatesAgents() {
    String agent =
        """
        {"agents":[
          {"name":"projects-agent","description":"Delivery agent","endpoint":"/agent/tasks",
           "roles":["solutions-user"]},
          {"name":"bad-agent","description":"No slash","endpoint":"agent/tasks"},
          {"name":"bad2","description":"No endpoint"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertFalse(result.ok());
    assertTrue(
        result.issues().stream().noneMatch(i -> i.contains("agents[0]")),
        "valid agent must pass: " + result.issues());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("agents[1].endpoint")), issues(result));
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("agents[2].endpoint")), issues(result));
  }

  // --- knowledge[] (Phase H) ---

  @Test
  void parseValidatesKnowledge() {
    String agent =
        """
        {"knowledge":[
          {"id":"playbook","title":"Delivery playbook","kind":"docsource","ref":"onedrive:/docs"},
          {"id":"faq","title":"FAQ","kind":"markdown","ref":"docs/faq.md"},
          {"id":"bad","title":"Bad kind","kind":"sitemap","ref":"x"},
          {"id":"bad2","title":"No ref","kind":"url"}
        ]}""";
    ManifestValidator.Result result = validator.parse(manifest(agent));

    assertFalse(result.ok());
    assertTrue(
        result.issues().stream().noneMatch(i -> i.contains("knowledge[0]"))
            && result.issues().stream().noneMatch(i -> i.contains("knowledge[1]")),
        "valid knowledge must pass: " + result.issues());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("knowledge[2].kind")), issues(result));
    assertTrue(
        result.issues().stream().anyMatch(i -> i.contains("knowledge[3].ref")), issues(result));
  }

  private String issues(ManifestValidator.Result result) {
    return result.issues().toString();
  }
}
