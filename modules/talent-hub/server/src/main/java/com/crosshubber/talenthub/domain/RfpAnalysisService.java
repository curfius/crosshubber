package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.talenthub.agent.LlmClient;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI capture analysis (P1.3): structured extraction from the raw RFP description via the module
 * {@link LlmClient}. Fail-soft by design — capture is never blocked: unconfigured credentials yield
 * {@code status=unconfigured}, transport/malformed yield {@code status=failed}; the draft is always
 * created. Runs OUTSIDE any DB transaction (HTTP call).
 */
@Service
public class RfpAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(RfpAnalysisService.class);

  private static final String SYSTEM_PROMPT =
      "You extract structured staffing-request data from free-text RFP/RFQ descriptions for a "
          + "staffing agency. Respond with ONLY a JSON object — no prose, no markdown fences. "
          + "Shape: {\"title\": string (short engagement title), \"deadline\": ISO-8601 date or "
          + "null, \"positions\": [{\"title\": string, \"headcount\": integer}], \"requirements\": "
          + "{\"skills\": [string], \"seniority\": string or null, \"languages\": [string], "
          + "\"description\": string}, \"openQuestions\": [string]}. Use the provided hints when "
          + "the text is ambiguous.";

  private static final int MAX_POSITIONS = 50;
  private static final int MAX_OPEN_QUESTIONS = 20;

  /** Analysis jsonb stored on the RFP + best-effort backfill fields extracted from it. */
  public record AnalysisResult(
      Map<String, Object> analysis,
      String title,
      Instant deadline,
      Map<String, Object> requirements) {}

  private final LlmClient llm;
  private final ModuleSettingsService settings;
  private final ObjectMapper objectMapper;

  public RfpAnalysisService(
      LlmClient llm, ModuleSettingsService settings, ObjectMapper objectMapper) {
    this.llm = llm;
    this.settings = settings;
    this.objectMapper = objectMapper;
  }

  public AnalysisResult analyze(String description, String titleHint, String deadlineHint) {
    Map<String, Object> analysis = new LinkedHashMap<>();
    analysis.put("positions", new ArrayList<Map<String, Object>>());
    analysis.put("openQuestions", new ArrayList<String>());
    analysis.put("model", null);
    analysis.put("at", Instant.now().toString());

    if (!llm.isConfigured()) {
      analysis.put("status", "unconfigured");
      return new AnalysisResult(analysis, null, null, Map.of());
    }
    ModuleSettingsService.EffectiveLlm effective = settings.effectiveLlm();
    analysis.put("model", effective.model());
    analysis.put("status", "configured");

    try {
      String user = userPrompt(description, titleHint, deadlineHint);
      String raw = llm.complete(SYSTEM_PROMPT, user);
      JsonNode parsed = parseLoose(raw);
      applyParsed(analysis, parsed);
      return new AnalysisResult(
          analysis,
          textOrNull(parsed.get("title")),
          instantOrNull(parsed.get("deadline")),
          requirementsOf(parsed));
    } catch (Exception e) {
      log.warn("[analysis] extraction failed: {}", e.getMessage());
      analysis.put("status", "failed");
      return new AnalysisResult(analysis, null, null, Map.of());
    }
  }

  private String userPrompt(String description, String titleHint, String deadlineHint) {
    StringBuilder user = new StringBuilder("RFP description:\n");
    if (titleHint != null && !titleHint.isBlank()) {
      user.append("(title hint: ").append(titleHint).append(")\n");
    }
    if (deadlineHint != null && !deadlineHint.isBlank()) {
      user.append("(deadline hint: ").append(deadlineHint).append(")\n");
    }
    return user.append(description == null ? "" : description).toString();
  }

  private void applyParsed(Map<String, Object> analysis, JsonNode parsed) {
    List<Map<String, Object>> positions = new ArrayList<>();
    if (parsed.path("positions").isArray()) {
      for (JsonNode position : parsed.path("positions")) {
        if (!position.isObject() || positions.size() >= MAX_POSITIONS) {
          break;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", textOrNull(position.get("title")));
        row.put(
            "headcount",
            position.path("headcount").isNumber() ? position.path("headcount").asInt() : 1);
        positions.add(row);
      }
    }
    analysis.put("positions", positions);

    List<String> questions = new ArrayList<>();
    if (parsed.path("openQuestions").isArray()) {
      for (JsonNode question : parsed.path("openQuestions")) {
        String text = textOrNull(question);
        if (text != null && questions.size() < MAX_OPEN_QUESTIONS) {
          questions.add(text);
        }
      }
    }
    analysis.put("openQuestions", questions);
  }

  private Map<String, Object> requirementsOf(JsonNode parsed) {
    JsonNode requirements = parsed.path("requirements");
    if (!requirements.isObject()) {
      return Map.of();
    }
    return objectMapper.convertValue(
        requirements, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  /** Direct JSON parse with a brace-span fallback (tolerates markdown-fenced responses). */
  private JsonNode parseLoose(String raw) {
    String text = raw == null ? "" : raw.trim();
    try {
      return objectMapper.readTree(text);
    } catch (Exception ignored) {
      int start = text.indexOf('{');
      int end = text.lastIndexOf('}');
      if (start < 0 || end <= start) {
        throw new IllegalArgumentException("llm response is not a JSON object");
      }
      return objectMapper.readTree(text.substring(start, end + 1));
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isString()) {
      return null;
    }
    String value = node.asString();
    return value == null || value.isBlank() ? null : value;
  }

  private static Instant instantOrNull(JsonNode node) {
    String raw = textOrNull(node);
    if (raw == null) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (Exception e) {
      return null;
    }
  }
}
