package com.crosshubber.staffing.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic candidate↔requirements scorer (AI_MODULES_PLAN "Matching strategy v1"): skills
 * overlap, seniority fit, language overlap, availability. No embeddings — revisit only if ranking
 * quality demands it. Pure functions; the highest-value unit-test target in the module.
 */
public final class MatchingService {

  private static final int SKILL_WEIGHT = 2;
  private static final int SENIORITY_EXACT = 3;
  private static final int SENIORITY_MISMATCH = 0;
  private static final int LANGUAGE_WEIGHT = 1;
  private static final int AVAILABILITY_WEIGHT = 2;
  private static final int MAX_SKILL_POINTS = 10;

  private MatchingService() {}

  /** Scored candidate row for match results. */
  public record ScoredCandidate(UUID candidateId, String name, long score, String rationale) {}

  /**
   * Scores one candidate against requirement fields.
   *
   * @param requirements {skills[], seniority, languages[], availability}
   */
  public static ScoredCandidate score(
      CandidateProfileEntity candidate, Map<String, Object> requirements) {
    long score = 0;
    List<String> hits = new ArrayList<>();

    List<String> wantedSkills = lowerList(requirements.get("skills"));
    List<String> candidateSkills = lowerList(candidate.getSkills());
    if (!wantedSkills.isEmpty()) {
      List<String> matched = wantedSkills.stream().filter(candidateSkills::contains).toList();
      score += Math.min(MAX_SKILL_POINTS, (long) matched.size() * SKILL_WEIGHT);
      if (!matched.isEmpty()) {
        hits.add("skills: " + String.join(", ", matched));
      }
    }

    String wantedSeniority = lower(requirements.get("seniority"));
    String candidateSeniority = lower(candidate.getSeniority());
    boolean seniorityMatch =
        wantedSeniority == null
            || candidateSeniority == null
            || candidateSeniority.contains(wantedSeniority)
            || wantedSeniority.contains(candidateSeniority);
    if (seniorityMatch && wantedSeniority != null && candidateSeniority != null) {
      score += SENIORITY_EXACT;
      hits.add("seniority: " + candidate.getSeniority());
    } else if (wantedSeniority != null && candidateSeniority != null) {
      score += SENIORITY_MISMATCH;
    }

    List<String> wantedLanguages = lowerList(requirements.get("languages"));
    List<String> candidateLanguages = lowerList(candidate.getLanguages());
    if (!wantedLanguages.isEmpty()) {
      List<String> matched = wantedLanguages.stream().filter(candidateLanguages::contains).toList();
      score += (long) matched.size() * LANGUAGE_WEIGHT;
      if (!matched.isEmpty()) {
        hits.add("languages: " + String.join(", ", matched));
      }
    }

    String wantedAvailability = lower(requirements.get("availability"));
    String candidateAvailability = lower(candidate.getAvailability());
    if (wantedAvailability != null
        && candidateAvailability != null
        && (candidateAvailability.contains(wantedAvailability)
            || wantedAvailability.contains(candidateAvailability))) {
      score += AVAILABILITY_WEIGHT;
      hits.add("availability: " + candidate.getAvailability());
    }

    String rationale = hits.isEmpty() ? "no direct requirement matches" : String.join("; ", hits);
    return new ScoredCandidate(candidate.getId(), candidate.getName(), score, rationale);
  }

  /** Ranks candidates by score desc, returns the top N. */
  public static List<ScoredCandidate> rank(
      List<CandidateProfileEntity> candidates, Map<String, Object> requirements, int topN) {
    return candidates.stream()
        .map(c -> score(c, requirements))
        .sorted((a, b) -> Long.compare(b.score(), a.score()))
        .limit(Math.max(0, topN))
        .toList();
  }

  private static List<String> lowerList(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        String value = lower(item);
        if (value != null) {
          out.add(value);
        }
      }
    }
    return out;
  }

  private static String lower(Object raw) {
    return raw instanceof String s && !s.isBlank() ? s.trim().toLowerCase(Locale.ROOT) : null;
  }
}
