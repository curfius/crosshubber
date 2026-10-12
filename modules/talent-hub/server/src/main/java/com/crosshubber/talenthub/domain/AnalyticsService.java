package com.crosshubber.talenthub.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analytics aggregation (P3.3): counts by phase/serviceType/client, outcome counts, time-in-phase
 * from the phase history, process funnel, placements (onboarded) per source and assessment-verdict
 * → outcome. The computation is a pure function over rows (unit-test target); the service only
 * fetches.
 */
@Service
public class AnalyticsService {

  public record AnalyticsSummary(
      String period,
      Map<String, Long> rfpCountsByPhase,
      Map<String, Long> rfpCountsByServiceType,
      Map<String, Long> outcomeCounts,
      List<Map<String, Object>> topClients,
      Map<String, Long> timeInPhaseHours,
      Map<String, Long> funnel,
      long placements,
      Map<String, Long> placementsBySource,
      Map<String, Map<String, Long>> verdictOutcomes) {}

  enum Period {
    DAYS_30(Duration.ofDays(30)),
    DAYS_90(Duration.ofDays(90)),
    ALL(null);

    final Duration duration;

    Period(Duration duration) {
      this.duration = duration;
    }

    boolean includes(Instant at, Instant now) {
      return duration == null || (at != null && !at.isBefore(now.minus(duration)));
    }

    String label() {
      return duration == null ? "all" : duration.toDays() + "d";
    }

    static Period parse(String raw) {
      if (raw == null || raw.isBlank() || "all".equals(raw)) {
        return ALL;
      }
      return switch (raw) {
        case "30d" -> DAYS_30;
        case "90d" -> DAYS_90;
        default ->
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be 30d|90d|all");
      };
    }
  }

  private final RfpRepository rfpRepository;
  private final CandidateProcessRepository processRepository;
  private final RfpPhaseHistoryRepository historyRepository;
  private final CandidateProfileRepository candidateRepository;

  public AnalyticsService(
      RfpRepository rfpRepository,
      CandidateProcessRepository processRepository,
      RfpPhaseHistoryRepository historyRepository,
      CandidateProfileRepository candidateRepository) {
    this.rfpRepository = rfpRepository;
    this.processRepository = processRepository;
    this.historyRepository = historyRepository;
    this.candidateRepository = candidateRepository;
  }

  @Transactional(readOnly = true)
  public AnalyticsSummary summary(String periodRaw) {
    Period period = Period.parse(periodRaw);
    Instant now = Instant.now();
    return compute(
        rfpRepository.findAll(),
        processRepository.findAll(),
        historyRepository.findAll(),
        candidateRepository.findAll(),
        period,
        now);
  }

  static AnalyticsSummary compute(
      List<RfpEntity> rfps,
      List<CandidateProcessEntity> processes,
      List<RfpPhaseHistoryEntity> histories,
      List<CandidateProfileEntity> candidates,
      Period period,
      Instant now) {
    List<RfpEntity> periodRfps =
        rfps.stream().filter(r -> period.includes(r.getCreatedAt(), now)).toList();

    Map<String, Long> rfpCountsByPhase =
        periodRfps.stream()
            .collect(Collectors.groupingBy(r -> r.getPhase().value(), Collectors.counting()));
    Map<String, Long> rfpCountsByServiceType =
        periodRfps.stream()
            .collect(
                Collectors.groupingBy(
                    r -> r.getServiceType() == null ? "unknown" : r.getServiceType(),
                    Collectors.counting()));
    Map<String, Long> outcomeCounts = new LinkedHashMap<>();
    outcomeCounts.put(
        "active", periodRfps.stream().filter(r -> !r.getPhase().isManualTerminal()).count());
    for (String terminal : List.of("closed", "rejected", "archived")) {
      outcomeCounts.put(
          terminal, periodRfps.stream().filter(r -> terminal.equals(r.getPhase().value())).count());
    }

    List<Map<String, Object>> topClients =
        periodRfps.stream()
            .collect(Collectors.groupingBy(RfpEntity::getClientId, Collectors.counting()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(5)
            .map(
                entry -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("clientId", entry.getKey().toString());
                  row.put("rfpCount", entry.getValue());
                  return row;
                })
            .toList();

    Map<String, Long> timeInPhaseHoursAverages = timeInPhaseHours(histories, periodRfps, now);

    Map<String, Long> funnel =
        processes.stream()
            .collect(Collectors.groupingBy(p -> p.getStage().value(), Collectors.counting()));

    List<CandidateProcessEntity> onboarded =
        processes.stream().filter(p -> p.getStage() == ProcessStage.ONBOARDED).toList();
    Map<UUID, String> sourceByCandidate =
        candidates.stream()
            .collect(
                Collectors.toMap(
                    CandidateProfileEntity::getId,
                    c -> c.getSourceRef() == null ? "unknown" : prefixOf(c.getSourceRef()),
                    (a, b) -> a));
    Map<String, Long> placementsBySource =
        onboarded.stream()
            .collect(
                Collectors.groupingBy(
                    p -> sourceByCandidate.getOrDefault(p.getCandidateId(), "unknown"),
                    Collectors.counting()));

    Map<String, Map<String, Long>> verdictOutcomes = new LinkedHashMap<>();
    for (CandidateProcessEntity process : processes) {
      if (process.getFit() == null
          || (process.getStage() != ProcessStage.ONBOARDED
              && process.getStage() != ProcessStage.REJECTED)) {
        continue;
      }
      Object overall = process.getFit().get("overall");
      String outcome = process.getStage() == ProcessStage.ONBOARDED ? "onboarded" : "rejected";
      if (overall != null) {
        verdictOutcomes
            .computeIfAbsent(overall.toString(), k -> new LinkedHashMap<>())
            .merge(outcome, 1L, Long::sum);
      }
    }

    return new AnalyticsSummary(
        period.label(),
        rfpCountsByPhase,
        rfpCountsByServiceType,
        outcomeCounts,
        topClients,
        timeInPhaseHoursAverages,
        funnel,
        onboarded.size(),
        placementsBySource,
        verdictOutcomes);
  }

  static Map<String, Long> timeInPhaseHours(
      List<RfpPhaseHistoryEntity> histories, List<RfpEntity> rfps, Instant now) {
    Map<UUID, List<RfpPhaseHistoryEntity>> byRfp =
        histories.stream()
            .filter(h -> h.getAt() != null)
            .collect(Collectors.groupingBy(RfpPhaseHistoryEntity::getRfpId));
    Map<String, List<Long>> hoursByPhase = new LinkedHashMap<>();
    for (RfpEntity rfp : rfps) {
      List<RfpPhaseHistoryEntity> sorted =
          byRfp.getOrDefault(rfp.getId(), List.of()).stream()
              .sorted(java.util.Comparator.comparing(RfpPhaseHistoryEntity::getAt))
              .toList();
      for (int i = 0; i < sorted.size(); i++) {
        RfpPhaseHistoryEntity current = sorted.get(i);
        Instant end = i + 1 < sorted.size() ? sorted.get(i + 1).getAt() : now;
        long hours = Math.max(0, Duration.between(current.getAt(), end).toHours());
        if (current.getToPhase() != null) {
          hoursByPhase
              .computeIfAbsent(current.getToPhase().value(), k -> new java.util.ArrayList<>())
              .add(hours);
        }
      }
    }
    Map<String, Long> averages = new LinkedHashMap<>();
    for (Map.Entry<String, List<Long>> entry : hoursByPhase.entrySet()) {
      List<Long> values = entry.getValue();
      averages.put(
          entry.getKey(), values.stream().mapToLong(Long::longValue).sum() / values.size());
    }
    return averages;
  }

  private static String prefixOf(String sourceRef) {
    int colon = sourceRef.indexOf(':');
    return colon > 0 ? sourceRef.substring(0, colon) : sourceRef;
  }
}
