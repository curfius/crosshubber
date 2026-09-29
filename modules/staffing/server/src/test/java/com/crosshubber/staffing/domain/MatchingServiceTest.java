package com.crosshubber.staffing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Deterministic scorer (AI_MODULES_PLAN "Matching strategy v1") — the module's core logic. */
class MatchingServiceTest {

  private static CandidateProfileEntity candidate(
      String name,
      List<String> skills,
      String seniority,
      List<String> languages,
      String availability) {
    CandidateProfileEntity c = new CandidateProfileEntity();
    ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
    c.setName(name);
    c.setSkills(skills);
    c.setSeniority(seniority);
    c.setLanguages(languages);
    c.setAvailability(availability);
    return c;
  }

  private static Map<String, Object> requirements(
      List<String> skills, String seniority, List<String> languages, String availability) {
    Map<String, Object> req = new java.util.HashMap<>();
    if (skills != null) {
      req.put("skills", skills);
    }
    if (seniority != null) {
      req.put("seniority", seniority);
    }
    if (languages != null) {
      req.put("languages", languages);
    }
    if (availability != null) {
      req.put("availability", availability);
    }
    return req;
  }

  @Test
  void skillsDriveTheScore() {
    var strong =
        MatchingService.score(
            candidate("A", List.of("java", "spring", "angular"), "senior", List.of(), null),
            requirements(List.of("java", "spring", "angular", "kafka"), "senior", null, null));
    var weak =
        MatchingService.score(
            candidate("B", List.of("react"), "senior", List.of(), null),
            requirements(List.of("java", "spring", "angular", "kafka"), "senior", null, null));

    assertThat(strong.score()).isGreaterThan(weak.score());
    assertThat(strong.rationale()).contains("java", "spring", "angular");
    assertThat(weak.rationale()).doesNotContain("skills");
  }

  @Test
  void skillPointsAreCapped() {
    var many =
        MatchingService.score(
            candidate(
                "A",
                List.of("java", "spring", "docker", "kubernetes", "aws", "rest", "sql", "kafka"),
                "senior",
                List.of(),
                null),
            requirements(
                List.of("java", "spring", "docker", "kubernetes", "aws", "rest", "sql", "kafka"),
                null,
                null,
                null));

    assertThat(many.score()).isLessThanOrEqualTo(10);
  }

  @Test
  void seniorityAndLanguagesAndAvailabilityContribute() {
    var full =
        MatchingService.score(
            candidate("A", List.of("java"), "senior", List.of("en", "pt"), "immediate"),
            requirements(List.of("java"), "senior", List.of("en", "pt"), "immediate"));
    var skillsOnly =
        MatchingService.score(
            candidate("B", List.of("java"), null, List.of(), null),
            requirements(List.of("java"), "senior", List.of("en", "pt"), "immediate"));

    assertThat(full.score()).isGreaterThan(skillsOnly.score());
    assertThat(full.rationale()).contains("seniority", "languages", "availability");
  }

  @Test
  void caseInsensitiveMatching() {
    var scored =
        MatchingService.score(
            candidate("A", List.of("Java", "Spring Boot"), "Senior", List.of("EN"), null),
            requirements(List.of("java", "spring boot"), "senior", List.of("en"), null));

    assertThat(scored.score()).isEqualTo(8);
  }

  @Test
  void rankOrdersByScoreDescAndLimitsTopN() {
    var candidates =
        List.of(
            candidate("weak", List.of("react"), "junior", List.of(), null),
            candidate("best", List.of("java", "spring"), "senior", List.of("en"), "immediate"),
            candidate("middle", List.of("java"), "senior", List.of(), null));

    var ranked =
        MatchingService.rank(
            candidates,
            requirements(List.of("java", "spring"), "senior", List.of("en"), "immediate"),
            2);

    assertThat(ranked).hasSize(2);
    assertThat(ranked.get(0).name()).isEqualTo("best");
    assertThat(ranked.get(1).name()).isEqualTo("middle");
  }

  @Test
  void missingRequirementFieldsDoNotCrash() {
    var scored = MatchingService.score(candidate("A", List.of(), null, List.of(), null), Map.of());

    assertThat(scored.score()).isZero();
    assertThat(scored.rationale()).isEqualTo("no direct requirement matches");
  }
}
