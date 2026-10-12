package com.crosshubber.talenthub.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Match-runs (P3.2/P3.6): persisted deterministic rankings with engine provenance. Ranking ships
 * rules-only this release — jev/llm produce per-process fit assessments instead; attempting a
 * non-rules match-run is rejected with a clear message rather than silently substituting engines.
 */
@Service
public class MatchRunService {

  private static final int DEFAULT_TOP_N = 5;
  private static final int MAX_TOP_N = 20;

  private final RfpRepository rfpRepository;
  private final CandidateProfileRepository candidateRepository;
  private final MatchRunRepository matchRunRepository;

  public MatchRunService(
      RfpRepository rfpRepository,
      CandidateProfileRepository candidateRepository,
      MatchRunRepository matchRunRepository) {
    this.rfpRepository = rfpRepository;
    this.candidateRepository = candidateRepository;
    this.matchRunRepository = matchRunRepository;
  }

  /** Ad-hoc ranking (persistence-free; used by tools + online matching). */
  public List<MatchingService.ScoredCandidate> rank(Map<String, Object> requirements, int topN) {
    return MatchingService.rank(candidateRepository.findAll(), requirements, effectiveTopN(topN));
  }

  @Transactional
  public MatchRunEntity createMatchRun(UUID rfpId, Integer topN, String engine, String actor) {
    if (engine != null && !"rules".equals(engine)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "match runs rank deterministically this release (engine must be rules); use "
              + "fit assessments for the jev/llm engines");
    }
    RfpEntity rfp =
        rfpRepository
            .findById(rfpId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
    List<MatchingService.ScoredCandidate> ranked =
        MatchingService.rank(
            candidateRepository.findAll(), rfp.getRequirements(), effectiveTopN(topN));
    List<Map<String, Object>> results = new ArrayList<>();
    for (MatchingService.ScoredCandidate scored : ranked) {
      Map<String, Object> row = new HashMap<>();
      row.put("candidateId", scored.candidateId().toString());
      row.put("name", scored.name());
      row.put("score", scored.score());
      row.put("rationale", scored.rationale());
      results.add(row);
    }
    MatchRunEntity run = new MatchRunEntity();
    run.setRfpId(rfpId);
    run.getParams().put("topN", effectiveTopN(topN));
    run.setStatus(MatchRunEntity.RunStatus.DONE);
    run.setResults(results);
    run.setEngine("rules");
    run.setCreatedBy(actor == null || actor.isBlank() ? "unknown" : actor);
    return matchRunRepository.save(run);
  }

  private static int effectiveTopN(Integer requested) {
    int value = requested == null ? DEFAULT_TOP_N : requested;
    return Math.max(1, Math.min(MAX_TOP_N, value));
  }
}
