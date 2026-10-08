package com.crosshubber.portal.modules.msgcenter.publish;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.events.EnvelopeValidator;
import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.modules.msgcenter.templates.MsgCenterTemplateService;
import com.crosshubber.portal.modules.registry.modules.ModulesService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publish path shared by builtin senders and the third-party HTTP endpoint (plan §6): template
 * resolution → validate envelope → moduleKey-exists check → JetStream publish.
 */
@Service
public class MsgCenterPublishService {

  static String subjectOf(JsonNode envelope) {
    var node = envelope.get("subject");
    String declared = node != null && node.isString() ? node.asString() : null;
    // fallback: derive from type + moduleKey (subject is informational; routing needs a valid one)
    if (declared != null
        && (declared.startsWith("portal.msg.") || declared.startsWith("portal.task."))) {
      return declared;
    }
    String type = moduleKeyOfPrefix(envelope);
    var moduleNode = envelope.get("moduleKey");
    String moduleKey =
        moduleNode != null && moduleNode.isString() ? moduleNode.asString() : "unknown";
    return (type.startsWith("task") ? "portal.task." : "portal.msg.") + moduleKey + ".published";
  }

  static String moduleKeyOfPrefix(JsonNode envelope) {
    var node = envelope.get("type");
    return node != null && node.isString() ? node.asString() : "";
  }

  private final EnvelopeValidator envelopeValidator;
  private final ModulesService modulesService;
  private final MsgCenterTemplateService templateService;
  private final EventPublisher publisher;
  private final ObjectMapper mapper;

  public MsgCenterPublishService(
      EnvelopeValidator envelopeValidator,
      ModulesService modulesService,
      MsgCenterTemplateService templateService,
      EventPublisher publisher,
      ObjectMapper mapper) {
    this.envelopeValidator = envelopeValidator;
    this.modulesService = modulesService;
    this.templateService = templateService;
    this.publisher = publisher;
    this.mapper = mapper;
  }

  /**
   * Validates + publishes; template refs are expanded first (strict; only inline overrides).
   *
   * @return the stream sequence of the published message
   */
  public long publish(String callerKey, JsonNode rawEnvelope) {
    JsonNode envelope = resolveTemplate(rawEnvelope);
    if (!modulesService.exists(moduleKeyOf(envelope))) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown moduleKey");
    }
    envelopeValidator.validate(envelope);
    String subject = subjectOf(envelope);
    var result = publisher.publish(subject, envelope.toString());
    if (!result.published()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "event transport disabled");
    }
    return result.streamSeq();
  }

  /**
   * Template expansion (decision 15): when {@code task.template} is declared, the stored shape
   * replaces envelope fields/sections; only the runtime knobs may ride along inline. Provenance
   * markers ({@code task.template{key,version}}) ride the stored envelope; strict resolution —
   * unknown key/version surfaces as 422 via the template service.
   */
  private JsonNode resolveTemplate(JsonNode envelope) {
    JsonNode task = envelope.get("task");
    if (task == null || !task.isObject()) {
      return envelope;
    }
    JsonNode ref = task.get("template");
    if (ref == null || !ref.isObject()) {
      return envelope;
    }
    String key = ref.hasNonNull("key") ? ref.get("key").asString() : null;
    int version =
        ref.hasNonNull("version") && ref.get("version").isIntegralNumber()
            ? ref.get("version").asInt()
            : -1;
    ObjectNode overrides = mapper.createObjectNode();
    for (String knob : List.of("completion", "completionEvent", "expiresAt", "claim")) {
      if (task.has(knob)) {
        overrides.set(knob, task.get(knob));
      }
    }
    JsonNode expanded = templateService.resolve(key, version, overrides);
    ObjectNode merged = (ObjectNode) envelope;
    ObjectNode taskNode = (ObjectNode) merged.get("task");
    // strip the inline shape keys, then graft the expanded fragment
    for (String shapeKey : List.of("fields", "sections", "template")) {
      taskNode.remove(shapeKey);
    }
    expanded.properties().forEach(entry -> taskNode.set(entry.getKey(), entry.getValue()));
    return merged;
  }

  static String moduleKeyOf(JsonNode envelope) {
    var node = envelope.get("moduleKey");
    return node != null && node.isString() ? node.asString() : null;
  }
}
