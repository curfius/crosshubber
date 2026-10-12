package com.crosshubber.talenthub.domain;

import org.springframework.stereotype.Component;

import com.crosshubber.talenthub.agent.LlmClient;

/**
 * LLM fit engine (D7): structured J/E/V prompt through the module LlmClient. Unconfigured LLM fails
 * the call (caller degrades, capture-style); malformed answers degrade per dimension.
 */
@Component
public class LlmFitEngine implements FitEngine {

  private static final String SYSTEM_PROMPT =
      "You assess candidate fit for a staffing position across three dimensions. Respond with "
          + "ONLY a JSON object — no prose, no markdown fences. Shape: {\"job\": "
          + "{\"verdict\": \"strong|maybe|skip\", \"rationale\": string, \"score\": number}, "
          + "\"environment\": {\"verdict\": ..., \"rationale\": ...}, \"value\": {\"verdict\": "
          + "..., \"rationale\": ...}}. Judge Job fit from skills/seniority/languages, Environment "
          + "fit from location/availability signals, Value fit from expectations vs budget. Use "
          + "\"maybe\" with \"insufficient data\" when signals are missing.";

  private final LlmClient llm;
  private final tools.jackson.databind.ObjectMapper objectMapper;

  public LlmFitEngine(LlmClient llm, tools.jackson.databind.ObjectMapper objectMapper) {
    this.llm = llm;
    this.objectMapper = objectMapper;
  }

  @Override
  public String key() {
    return "llm";
  }

  @Override
  public FitVerdict assess(
      CandidateProfileEntity candidate, PositionRequirements requirements, String actor)
      throws Exception {
    String raw =
        llm.complete(
            SYSTEM_PROMPT,
            "Candidate: "
                + candidate.getName()
                + ", skills "
                + candidate.getSkills()
                + ", seniority "
                + candidate.getSeniority()
                + ", languages "
                + candidate.getLanguages()
                + ", availability "
                + (candidate.getAvailability() == null ? "-" : candidate.getAvailability())
                + ". Position requirements: "
                + objectMapper.writeValueAsString(requirements.raw()));
    tools.jackson.databind.JsonNode parsed = parseLoose(raw);
    return FitVerdict.of(
        key(),
        dimensionOf(parsed, "job"),
        dimensionOf(parsed, "environment"),
        dimensionOf(parsed, "value"));
  }

  private tools.jackson.databind.JsonNode parseLoose(String raw) throws Exception {
    String text = raw == null ? "" : raw.trim();
    try {
      return objectMapper.readTree(text);
    } catch (Exception ignored) {
      int start = text.indexOf('{');
      int end = text.lastIndexOf('}');
      if (start < 0 || end <= start) {
        throw new IllegalStateException("llm response is not a JSON object");
      }
      return objectMapper.readTree(text.substring(start, end + 1));
    }
  }

  private FitVerdict.DimensionVerdict dimensionOf(
      tools.jackson.databind.JsonNode parsed, String key) {
    tools.jackson.databind.JsonNode dimension = parsed.path(key);
    if (!dimension.isObject()) {
      return FitVerdict.dimension(FitVerdict.MAYBE, "engine did not report this dimension", null);
    }
    String verdict =
        dimension.path("verdict").isString() ? dimension.path("verdict").asString() : null;
    String rationale =
        dimension.path("rationale").isString()
            ? dimension.path("rationale").asString()
            : "no rationale";
    String score =
        dimension.path("score").isNumber()
            ? String.valueOf(dimension.path("score").asDouble())
            : null;
    return FitVerdict.dimension(verdict == null ? FitVerdict.MAYBE : verdict, rationale, score);
  }
}
