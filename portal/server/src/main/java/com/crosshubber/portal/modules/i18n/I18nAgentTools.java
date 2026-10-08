package com.crosshubber.portal.modules.i18n;

import static com.crosshubber.portal.modules.aihub.agent.ToolSchemas.arg;
import static com.crosshubber.portal.modules.aihub.agent.ToolSchemas.schema;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * i18n agent tool (AI plan B1 seed, D5 provider split): label reads are a core capability, so the
 * declaration is platform-global (moduleKey null) — hiding the i18n admin UI does not remove the
 * agent's label access.
 */
@Service
public class I18nAgentTools implements BuiltinToolProvider {

  private static final int MAX_LABEL_ROWS = 200;
  private static final int DEFAULT_LABEL_LIMIT = 100;

  private final I18nService i18nService;
  private final ObjectMapper objectMapper;

  public I18nAgentTools(I18nService i18nService, ObjectMapper objectMapper) {
    this.i18nService = i18nService;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AgentTool> tools() {
    return List.of(
        AgentTool.builtin(
            "getI18nLabels",
            "Returns i18n label keys/values for a language, optionally filtered by key prefix. "
                + "Use a prefix — the full bundle is large.",
            schema(
                objectMapper,
                arg(
                    "language",
                    "string",
                    "Language code, e.g. en-GB. Defaults to the tenant default."),
                arg("prefix", "string", "Only labels whose key starts with this prefix."),
                arg("limit", "integer", "Max labels returned (default 100, max 200).")),
            false,
            List.of()));
  }

  @Override
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    String language = Texts.blankToNull(Texts.string(args.path("language").asString(null)));
    String prefix = Texts.blankToNull(Texts.string(args.path("prefix").asString(null)));
    int limit = intArg(args, "limit", DEFAULT_LABEL_LIMIT, 1, MAX_LABEL_ROWS);
    if (language == null) {
      language = i18nService.getSettingsRow().getDefaultLanguage();
    }
    Map<String, String> labels = i18nService.getLabels(language);
    ArrayNode out = objectMapper.createArrayNode();
    int total = 0;
    for (Map.Entry<String, String> e : labels.entrySet()) {
      if (prefix != null && !e.getKey().startsWith(prefix)) {
        continue;
      }
      total++;
      if (out.size() < limit) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("key", e.getKey());
        row.put("value", e.getValue());
        out.add(row);
      }
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.put("language", language);
    result.put("total", total);
    result.put("returned", out.size());
    result.set("labels", out);
    return result;
  }

  private static int intArg(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args.path(field);
    if (!node.isNumber()) {
      return fallback;
    }
    return Math.max(min, Math.min(max, node.asInt(fallback)));
  }
}
