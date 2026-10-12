package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Rules J/E/V engine (D7): score bands, insufficient-data degradation, worst-of aggregation. */
class RulesFitEngineTest {

  private final RulesFitEngine engine = new RulesFitEngine();

  private CandidateProfileEntity candidate(
      List<String> skills,
      String seniority,
      List<String> languages,
      String availability,
      Map<String, Object> expectations,
      Map<String, Object> assignment) {
    CandidateProfileEntity c = new CandidateProfileEntity();
    ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
    c.setName("Ana");
    c.setSkills(skills);
    c.setSeniority(seniority);
    c.setLanguages(languages);
    c.setAvailability(availability);
    c.setExpectations(expectations);
    c.setAssignment(assignment);
    return c;
  }

  @Test
  void strongJobFitMapsToStrongWithScore() {
    var verdict =
        engine.assess(
            candidate(
                List.of("java", "spring", "kafka"),
                "senior",
                List.of("en", "pt"),
                "immediate",
                null,
                null),
            PositionRequirements.from(
                Map.of(
                    "skills",
                    List.of("java", "spring", "kafka"),
                    "seniority",
                    "senior",
                    "languages",
                    List.of("en", "pt"),
                    "availability",
                    "immediate")),
            "u1");

    FitVerdict.DimensionVerdict job = verdict.dimensions().get("job");
    assertThat(job.verdict()).isEqualTo(FitVerdict.STRONG);
    assertThat(job.score()).isNotNull();
    assertThat(job.rationale()).contains("java");
    assertThat(verdict.engine()).isEqualTo("rules");
    assertThat(verdict.at()).isNotNull();
  }

  @Test
  void weakJobFitSkipsWithRationale() {
    var verdict =
        engine.assess(
            candidate(List.of("react"), "junior", List.of(), null, null, null),
            PositionRequirements.from(
                Map.of("skills", List.of("java", "kubernetes"), "seniority", "senior")),
            "u1");

    assertThat(verdict.dimensions().get("job").verdict()).isEqualTo(FitVerdict.SKIP);
    assertThat(verdict.dimensions().get("job").rationale()).contains("no direct");
  }

  @Test
  void missingSkillsOnBothSidesDegradeToMaybeInsufficient() {
    var verdict =
        engine.assess(
            candidate(List.of(), null, List.of(), null, null, null),
            PositionRequirements.from(Map.of()),
            "u1");

    assertThat(verdict.dimensions().get("job").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(verdict.dimensions().get("job").rationale()).contains("insufficient data");
  }

  @Test
  void valueComparesExpectationRateAgainstBudgetBands() {
    var strong =
        engine.assess(
            candidate(List.of(), null, List.of(), null, Map.of("rate", 440), null),
            PositionRequirements.from(Map.of("rate", 500)),
            "u1");
    var maybe =
        engine.assess(
            candidate(List.of(), null, List.of(), null, Map.of("rate", 570), null),
            PositionRequirements.from(Map.of("rate", 500)),
            "u1");
    var skip =
        engine.assess(
            candidate(List.of(), null, List.of(), null, Map.of("rate", 900), null),
            PositionRequirements.from(Map.of("rate", 500)),
            "u1");

    assertThat(strong.dimensions().get("value").verdict()).isEqualTo(FitVerdict.STRONG);
    assertThat(maybe.dimensions().get("value").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(skip.dimensions().get("value").verdict()).isEqualTo(FitVerdict.SKIP);
    assertThat(stringContains(strong.dimensions().get("value").rationale(), "440")).isTrue();
  }

  @Test
  void noticeConflictMakesValueMaybe() {
    var verdict =
        engine.assess(
            candidate(
                List.of(),
                null,
                List.of(),
                null,
                Map.of("rate", 440, "noticePeriod", "3 months"),
                null),
            PositionRequirements.from(Map.of("rate", 500, "noticePeriod", "immediate")),
            "u1");

    assertThat(verdict.dimensions().get("value").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(verdict.dimensions().get("value").rationale()).contains("notice");
  }

  @Test
  void overallIsWorstOfTheThreeDimensions() {
    var allStrong =
        FitVerdict.of(
            "rules",
            FitVerdict.dimension(FitVerdict.STRONG, "a", null),
            FitVerdict.dimension(FitVerdict.STRONG, "b", null),
            FitVerdict.dimension(FitVerdict.STRONG, "c", null));
    var anySkip =
        FitVerdict.of(
            "rules",
            FitVerdict.dimension(FitVerdict.STRONG, "a", null),
            FitVerdict.dimension(FitVerdict.SKIP, "b", null),
            FitVerdict.dimension(FitVerdict.MAYBE, "c", null));
    var anyMaybe =
        FitVerdict.of(
            "rules",
            FitVerdict.dimension(FitVerdict.STRONG, "a", null),
            FitVerdict.dimension(FitVerdict.MAYBE, "b", null),
            FitVerdict.dimension(FitVerdict.STRONG, "c", null));

    assertThat(allStrong.overall()).isEqualTo(FitVerdict.STRONG);
    assertThat(anySkip.overall()).isEqualTo(FitVerdict.SKIP);
    assertThat(anyMaybe.overall()).isEqualTo(FitVerdict.MAYBE);
  }

  @Test
  void environmentInsufficientDataWhenNoSignalsAtAll() {
    var verdict =
        engine.assess(
            candidate(List.of("java"), null, List.of(), null, null, null),
            PositionRequirements.from(Map.of("skills", List.of("java"))),
            "u1");

    assertThat(verdict.dimensions().get("environment").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(verdict.dimensions().get("environment").rationale()).contains("insufficient data");
  }

  @Test
  void environmentAvailabilityConflictIsMaybe() {
    var verdict =
        engine.assess(
            candidate(List.of("java"), null, List.of(), "3 months notice", null, null),
            PositionRequirements.from(
                Map.of("skills", List.of("java"), "availability", "immediate")),
            "u1");

    assertThat(verdict.dimensions().get("environment").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(verdict.dimensions().get("environment").rationale()).contains("conflict");
  }

  private static boolean stringContains(String value, String needle) {
    return value != null && value.contains(needle);
  }
}
