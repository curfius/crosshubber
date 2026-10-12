package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Rules fit engine (default, D7): maps the deterministic MatchingService score bands to a Job
 * verdict; Environment compares availability/location signals; Value compares expectations
 * (rate/notice) against the position fields. Missing data degenerates to {@code maybe} +
 * "insufficient data" — never a crash, never a silent strong.
 */
@Component
public class RulesFitEngine implements FitEngine {

  private static final int JOB_STRONG_FROM = 8;
  private static final int JOB_MAYBE_FROM = 3;

  @Override
  public String key() {
    return "rules";
  }

  @Override
  public FitVerdict assess(
      CandidateProfileEntity candidate, PositionRequirements requirements, String actor) {
    return FitVerdict.of(
        key(),
        jobVerdict(candidate, requirements),
        environmentVerdict(candidate, requirements),
        valueVerdict(candidate, requirements));
  }

  private FitVerdict.DimensionVerdict jobVerdict(
      CandidateProfileEntity candidate, PositionRequirements requirements) {
    boolean noWantedSkills = list(requirements.field("skills")).isEmpty();
    boolean noWantedLanguages = list(requirements.field("languages")).isEmpty();
    boolean noWantedSeniority = requirements.field("seniority") == null;
    if (noWantedSkills
        && noWantedLanguages
        && noWantedSeniority
        && candidate.getSkills().isEmpty()) {
      return FitVerdict.dimension(
          FitVerdict.MAYBE, "insufficient data (no skills on either side)", null);
    }
    MatchingService.ScoredCandidate scored = MatchingService.score(candidate, requirements.raw());
    String verdict =
        scored.score() >= JOB_STRONG_FROM
            ? FitVerdict.STRONG
            : scored.score() >= JOB_MAYBE_FROM ? FitVerdict.MAYBE : FitVerdict.SKIP;
    return FitVerdict.dimension(verdict, scored.rationale(), String.valueOf(scored.score()));
  }

  private FitVerdict.DimensionVerdict environmentVerdict(
      CandidateProfileEntity candidate, PositionRequirements requirements) {
    String positionAvailability = text(requirements.field("availability"));
    String candidateAvailability = text(candidate.getAvailability());
    String positionLocation = text(requirements.field("location"));
    String positionRemote = text(requirements.field("remote"));
    if (positionAvailability == null
        && positionLocation == null
        && positionRemote == null
        && candidateAvailability == null
        && candidate.getAssignment() == null) {
      return FitVerdict.dimension(
          FitVerdict.MAYBE, "insufficient data (no location/availability signals)", null);
    }
    StringBuilder rationale = new StringBuilder();
    String verdict = FitVerdict.STRONG;
    if (positionAvailability != null && candidateAvailability != null) {
      boolean aligns =
          candidateAvailability.contains(positionAvailability)
              || positionAvailability.contains(candidateAvailability);
      rationale
          .append("availability ")
          .append(candidateAvailability)
          .append(aligns ? " fits " : " may conflict with ")
          .append(positionAvailability);
      verdict = aligns ? FitVerdict.STRONG : FitVerdict.MAYBE;
    }
    if (positionRemote != null) {
      boolean candidateRemoteOpen =
          candidate.getExpectations() != null
              && candidate.getExpectations().get("remote") != null
              && Boolean.toString(true)
                  .equalsIgnoreCase(text(candidate.getExpectations().get("remote")));
      if (positionRemote.contains("none") && candidateRemoteOpen) {
        rationale.append("; remote expectation conflicts");
        verdict = FitVerdict.MAYBE;
      }
    }
    if (rationale.length() == 0) {
      rationale.append("no conflicts detected");
    }
    return FitVerdict.dimension(verdict, rationale.toString(), null);
  }

  private FitVerdict.DimensionVerdict valueVerdict(
      CandidateProfileEntity candidate, PositionRequirements requirements) {
    Double positionRate = number(requirements.field("rate"));
    String positionNotice = text(requirements.field("noticePeriod"));
    Map<String, Object> expectations = candidate.getExpectations();
    Double wantedRate = expectations == null ? null : number(expectations.get("rate"));
    String wantedNotice = expectations == null ? null : text(expectations.get("noticePeriod"));
    if ((positionRate == null && wantedRate == null)
        && (positionNotice == null && wantedNotice == null)) {
      return FitVerdict.dimension(
          FitVerdict.MAYBE, "insufficient data (no rate/notice signals)", null);
    }
    StringBuilder rationale = new StringBuilder();
    String verdict = FitVerdict.STRONG;
    if (positionRate != null && wantedRate != null && positionRate > 0) {
      double ratio = wantedRate / positionRate;
      verdict =
          ratio <= 1.10 ? FitVerdict.STRONG : ratio <= 1.25 ? FitVerdict.MAYBE : FitVerdict.SKIP;
      rationale
          .append("expectation ")
          .append(wantedRate)
          .append(" vs budget ")
          .append(positionRate);
    } else if (wantedRate != null) {
      rationale.append("expectation ").append(wantedRate).append(" without a position budget");
    }
    if (positionNotice != null && wantedNotice != null) {
      boolean aligns =
          wantedNotice.contains(positionNotice) || positionNotice.contains(wantedNotice);
      if (!aligns) {
        verdict = FitVerdict.MAYBE;
        rationale.append("; notice ").append(wantedNotice).append(" vs ").append(positionNotice);
      }
    }
    if (rationale.length() == 0) {
      rationale.append("no conflicts detected");
    }
    return FitVerdict.dimension(verdict, rationale.toString(), null);
  }

  private static List<String> list(Object raw) {
    List<String> out = new java.util.ArrayList<>();
    if (raw instanceof List<?> items) {
      for (Object item : items) {
        if (item != null) {
          out.add(item.toString().toLowerCase(java.util.Locale.ROOT));
        }
      }
    }
    return out;
  }

  private static String text(Object raw) {
    return raw == null ? null : raw.toString();
  }

  private static Double number(Object raw) {
    if (raw instanceof Number n) {
      return n.doubleValue();
    }
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
