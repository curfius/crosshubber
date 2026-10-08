package com.crosshubber.portal.modules.aihub.agent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared JSON Schema helpers for builtin tool argument declarations (AI plan D5). */
public final class ToolSchemas {

  private ToolSchemas() {}

  /** Named JSON Schema property definition. */
  public record Arg(String name, String type, String description) {}

  /** Named property shorthand. */
  public static Arg arg(String name, String type, String description) {
    return new Arg(name, type, description);
  }

  /** Builds a JSON Schema object node from named property definitions. */
  public static ObjectNode schema(ObjectMapper objectMapper, Arg... args) {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    for (Arg arg : args) {
      ObjectNode prop = properties.putObject(arg.name());
      prop.put("type", arg.type());
      prop.put("description", arg.description());
    }
    return schema;
  }
}
