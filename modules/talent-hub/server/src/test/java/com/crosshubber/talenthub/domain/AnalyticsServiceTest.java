package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Analytics aggregation (P3.3) — pure compute over seeded rows. */
class AnalyticsServiceTest {

  private static RfpEntity rfp(
      RfpPhase phase, String serviceType, UUID clientId, Instant createdAt) {
    RfpEntity r = new RfpEntity();
    ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
    r.setClientId(clientId);
    r.setTitle("t");
    r.setPhase(phase);
    r.setServiceType(serviceType);
    ReflectionTestUtils.setField(r, "createdAt", createdAt);
    return r;
  }

  private static CandidateProcessEntity process(ProcessStage stage, Map<String, Object> fit) {
    CandidateProcessEntity p = new CandidateProcessEntity();
    ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
    p.setRfpId(UUID.randomUUID());
    p.setPositionId(UUID.randomUUID());
    p.setCandidateId(UUID.randomUUID());
    p.setStage(stage);
    p.setFit(fit);
    return p;
  }

  @Test
  void computeCountsPhasesServiceTypesAndOutcomes() {
    UUID clientId = UUID.randomUUID();
    Instant now = Instant.now();
    List<RfpEntity> rfps =
        List.of(
            rfp(RfpPhase.APPROVED, "tm", clientId, now),
            rfp(RfpPhase.SOURCING, "tm", clientId, now),
            rfp(RfpPhase.CLOSED, "direct", clientId, now),
            rfp(RfpPhase.CLOSED, "direct", UUID.randomUUID(), now));

    AnalyticsService.AnalyticsSummary summary =
        AnalyticsService.compute(
            rfps, List.of(), List.of(), List.of(), AnalyticsService.Period.ALL, now);

    assertThat(summary.rfpCountsByPhase())
        .containsEntry("approved", 1L)
        .containsEntry("sourcing", 1L)
        .containsEntry("closed", 2L);
    assertThat(summary.rfpCountsByServiceType())
        .containsEntry("tm", 2L)
        .containsEntry("direct", 2L);
    assertThat(summary.outcomeCounts()).containsEntry("active", 2L).containsEntry("closed", 2L);
  }

  @Test
  void computeTimeInPhaseAveragesFromHistory() {
    Instant now = Instant.now();
    UUID rfpId = UUID.randomUUID();
    RfpEntity closed = rfp(RfpPhase.CLOSED, "tm", UUID.randomUUID(), now.minusSeconds(86400));
    ReflectionTestUtils.setField(closed, "id", rfpId);

    RfpPhaseHistoryEntity first = new RfpPhaseHistoryEntity();
    first.setRfpId(rfpId);
    first.setFromPhase(null);
    first.setToPhase(RfpPhase.APPROVED);
    ReflectionTestUtils.setField(first, "at", now.minusSeconds(3600 * 10));
    RfpPhaseHistoryEntity second = new RfpPhaseHistoryEntity();
    second.setRfpId(rfpId);
    second.setFromPhase(RfpPhase.APPROVED);
    second.setToPhase(RfpPhase.SOURCING);
    ReflectionTestUtils.setField(second, "at", now.minusSeconds(3600 * 4));

    AnalyticsService.AnalyticsSummary summary =
        AnalyticsService.compute(
            List.of(closed),
            List.of(),
            List.of(first, second),
            List.of(),
            AnalyticsService.Period.ALL,
            now);

    // approved segment: 10h -> 4h change = 6h; sourcing segment: 4h → now (4h).
    assertThat(summary.timeInPhaseHours())
        .containsEntry("approved", 6L)
        .containsEntry("sourcing", 4L);
  }

  @Test
  void computeFunnelAndPlacementsBySource() {
    Instant now = Instant.now();
    CandidateProfileEntity internal = new CandidateProfileEntity();
    ReflectionTestUtils.setField(internal, "id", UUID.randomUUID());
    internal.setSourceRef("manual:abc");
    CandidateProfileEntity sourced = new CandidateProfileEntity();
    ReflectionTestUtils.setField(sourced, "id", UUID.randomUUID());
    sourced.setSourceRef("src-cv-dan");

    CandidateProcessEntity onboardedInternal = process(ProcessStage.ONBOARDED, null);
    ReflectionTestUtils.setField(onboardedInternal, "candidateId", internal.getId());
    CandidateProcessEntity onboardedSourced = process(ProcessStage.ONBOARDED, null);
    ReflectionTestUtils.setField(onboardedSourced, "candidateId", sourced.getId());
    CandidateProcessEntity pooled = process(ProcessStage.POOLED, null);

    AnalyticsService.AnalyticsSummary summary =
        AnalyticsService.compute(
            List.of(),
            List.of(onboardedInternal, onboardedSourced, pooled),
            List.of(),
            List.of(internal, sourced),
            AnalyticsService.Period.ALL,
            now);

    assertThat(summary.funnel()).containsEntry("onboarded", 2L).containsEntry("pooled", 1L);
    assertThat(summary.placements()).isEqualTo(2);
    assertThat(summary.placementsBySource())
        .containsEntry("manual", 1L)
        .containsEntry("src-cv-dan", 1L);
  }

  @Test
  void computeVerdictOutcomesForAssessedFinalStates() {
    Instant now = Instant.now();
    CandidateProcessEntity wonStrong =
        process(
            ProcessStage.ONBOARDED,
            Map.of(
                "overall",
                FitVerdict.STRONG,
                "engine",
                "rules",
                "at",
                now.toString(),
                "dimensions",
                Map.of(
                    "job", Map.of("verdict", "strong", "rationale", "x"),
                    "environment", Map.of("verdict", "strong", "rationale", "x"),
                    "value", Map.of("verdict", "strong", "rationale", "x"))));
    CandidateProcessEntity lostWeak =
        process(
            ProcessStage.REJECTED,
            Map.of(
                "overall",
                FitVerdict.SKIP,
                "engine",
                "rules",
                "at",
                now.toString(),
                "dimensions",
                Map.of(
                    "job", Map.of("verdict", "skip", "rationale", "x"),
                    "environment", Map.of("verdict", "skip", "rationale", "x"),
                    "value", Map.of("verdict", "skip", "rationale", "x"))));
    CandidateProcessEntity unassessed = process(ProcessStage.SUBMITTED, null);

    AnalyticsService.AnalyticsSummary summary =
        AnalyticsService.compute(
            List.of(),
            List.of(wonStrong, lostWeak, unassessed),
            List.of(),
            List.of(),
            AnalyticsService.Period.ALL,
            now);

    assertThat(summary.verdictOutcomes()).containsEntry("strong", Map.of("onboarded", 1L));
    assertThat(summary.verdictOutcomes()).containsEntry("skip", Map.of("rejected", 1L));
  }

  @Test
  void periodParsingRejectsGarbage() {
    assertThatThrownBy(
            () ->
                AnalyticsService.compute(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    AnalyticsService.Period.parse("weird"),
                    Instant.now()))
        .isInstanceOf(Exception.class);
  }
}
