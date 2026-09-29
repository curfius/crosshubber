package com.crosshubber.staffing.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.staffing.docs.DocumentSource;

/**
 * Staffing domain service: RFP lifecycle, CV ingestion (scan + naive extraction in v1), search and
 * match runs. Status transitions are validated; mutating agent tools gate on the portal
 * confirmation flow.
 */
@Service
public class StaffingService {

  /** Naive skill vocabulary for v1 extraction (LLM-assisted extraction is a later slice). */
  static final List<String> SKILL_DICTIONARY =
      List.of(
          "java",
          "spring",
          "spring boot",
          "angular",
          "react",
          "typescript",
          "javascript",
          "python",
          "sql",
          "postgresql",
          "docker",
          "kubernetes",
          "aws",
          "azure",
          "kafka",
          "microservices",
          "rest",
          "graphql",
          "terraform",
          "c#");

  private static final int DEFAULT_TOP_N = 5;
  private static final int MAX_TOP_N = 20;

  private final RfpRepository rfpRepository;
  private final CandidateProfileRepository candidateRepository;
  private final MatchRunRepository matchRunRepository;
  private final ShortlistRepository shortlistRepository;
  private final DocumentSource documentSource;

  public StaffingService(
      RfpRepository rfpRepository,
      CandidateProfileRepository candidateRepository,
      MatchRunRepository matchRunRepository,
      ShortlistRepository shortlistRepository,
      DocumentSource documentSource) {
    this.rfpRepository = rfpRepository;
    this.candidateRepository = candidateRepository;
    this.matchRunRepository = matchRunRepository;
    this.shortlistRepository = shortlistRepository;
    this.documentSource = documentSource;
  }

  // ── RFPs ─────────────────────────────────────────────────────────────

  @Transactional
  public RfpEntity createRfp(
      String client,
      String title,
      String kind,
      java.time.Instant deadline,
      Map<String, Object> requirements,
      String specDocRef,
      String actor) {
    if (client == null || client.isBlank()) {
      throw badRequest("client is required");
    }
    if (title == null || title.isBlank()) {
      throw badRequest("title is required");
    }
    if (!"rfp".equals(kind) && !"rfq".equals(kind)) {
      throw badRequest("kind must be rfp or rfq");
    }
    if (deadline == null) {
      throw badRequest("deadline is required");
    }
    RfpEntity rfp = new RfpEntity();
    rfp.setClient(client.trim());
    rfp.setTitle(title.trim());
    rfp.setKind(kind);
    rfp.setDeadline(deadline);
    rfp.setRequirements(requirements == null ? new HashMap<>() : requirements);
    rfp.setSpecDocRef(specDocRef);
    rfp.setCreatedBy(actor == null || actor.isBlank() ? "unknown" : actor);
    return rfpRepository.save(rfp);
  }

  @Transactional(readOnly = true)
  public List<RfpEntity> listRfps(RfpStatus status) {
    return status == null
        ? rfpRepository.findByStatusNotOrderByCreatedAtDesc(RfpStatus.ARCHIVED)
        : rfpRepository.findByStatus(status);
  }

  @Transactional(readOnly = true)
  public RfpEntity getRfp(UUID id) {
    return rfpRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
  }

  @Transactional
  public RfpEntity transition(UUID id, String toStatusRaw, Map<String, Object> requirementsPatch) {
    RfpStatus target = RfpStatus.fromString(toStatusRaw);
    RfpEntity rfp = getRfp(id);
    if (!rfp.getStatus().canTransitionTo(target)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "illegal transition " + rfp.getStatus().value() + " -> " + target.value());
    }
    if (requirementsPatch != null && !requirementsPatch.isEmpty()) {
      Map<String, Object> merged = new HashMap<>(rfp.getRequirements());
      merged.putAll(requirementsPatch);
      rfp.setRequirements(merged);
    }
    rfp.setStatus(target);
    return rfpRepository.save(rfp);
  }

  // ── CV ingestion ─────────────────────────────────────────────────────

  /**
   * Scans one CV document into a parsed profile (v1: naive dictionary extraction over the fetched
   * text). Re-scanning the same source ref replaces the previous profile (marked STALE).
   */
  @Transactional
  public CandidateProfileEntity ingestCv(String documentRef, String title, String text) {
    if (text == null || text.isBlank()) {
      throw badRequest("cv text is empty for " + documentRef);
    }
    candidateRepository
        .findBySourceRef(documentRef)
        .forEach(
            stale -> {
              stale.setStatus(CandidateProfileEntity.ProfileStatus.STALE);
              candidateRepository.save(stale);
            });

    String lower = text.toLowerCase(Locale.ROOT);
    CandidateProfileEntity profile = new CandidateProfileEntity();
    profile.setSourceRef(documentRef);
    profile.setName(title != null && !title.isBlank() ? title.trim() : documentRef);
    profile.setHeadline(extractLine(text, "summary|profile|about"));
    profile.setSkills(SKILL_DICTIONARY.stream().filter(lower::contains).toList());
    profile.setSeniority(extractSeniority(lower));
    profile.setLanguages(extractLanguages(lower));
    profile.setAvailability(extractAvailability(lower));
    profile.setStatus(CandidateProfileEntity.ProfileStatus.PARSED);
    return candidateRepository.save(profile);
  }

  /** Fetches a CV by ref through the module's own connector, then ingests it. */
  @Transactional
  public CandidateProfileEntity scanCv(String documentRef, String title) {
    String text;
    try {
      text = documentSource.fetchDocument(documentRef).text();
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "cv fetch failed: " + (e.getMessage() != null ? e.getMessage() : "transport error"));
    }
    return ingestCv(documentRef, title, text);
  }

  @Transactional(readOnly = true)
  public List<CandidateProfileEntity> listCandidates() {
    return candidateRepository.findAll();
  }

  /** Naive profile search: term overlap over name/headline/skills/languages. */
  @Transactional(readOnly = true)
  public List<CandidateProfileEntity> searchCvs(String query) {
    if (query == null || query.isBlank()) {
      return candidateRepository.findAll();
    }
    String[] terms = query.toLowerCase(Locale.ROOT).split("\\W+");
    return candidateRepository.findAll().stream()
        .filter(
            c -> {
              String haystack =
                  (c.getName()
                          + " "
                          + c.getHeadline()
                          + " "
                          + c.getSkills()
                          + " "
                          + c.getLanguages()
                          + " "
                          + c.getSeniority())
                      .toLowerCase(Locale.ROOT);
              for (String term : terms) {
                if (term.length() >= 3 && haystack.contains(term)) {
                  return true;
                }
              }
              return false;
            })
        .toList();
  }

  // ── Matching ─────────────────────────────────────────────────────────

  /** Runs a match and persists it — the mutating tool target (needs portal confirmation). */
  @Transactional
  public MatchRunEntity createMatchRun(UUID rfpId, int topN, String actor) {
    RfpEntity rfp = getRfp(rfpId);
    int effectiveTopN = Math.max(1, Math.min(MAX_TOP_N, topN <= 0 ? DEFAULT_TOP_N : topN));
    List<Map<String, Object>> results = new ArrayList<>();
    for (MatchingService.ScoredCandidate scored :
        MatchingService.rank(candidateRepository.findAll(), rfp.getRequirements(), effectiveTopN)) {
      Map<String, Object> row = new HashMap<>();
      row.put("candidateId", scored.candidateId().toString());
      row.put("name", scored.name());
      row.put("score", scored.score());
      row.put("rationale", scored.rationale());
      results.add(row);
    }
    MatchRunEntity run = new MatchRunEntity();
    run.setRfpId(rfpId);
    run.getParams().put("topN", effectiveTopN);
    run.setStatus(MatchRunEntity.RunStatus.DONE);
    run.setResults(results);
    run.setCreatedBy(actor == null || actor.isBlank() ? "unknown" : actor);
    return matchRunRepository.save(run);
  }

  /** Ad-hoc match without persistence (read tool). */
  @Transactional(readOnly = true)
  public List<MatchingService.ScoredCandidate> matchCandidates(
      Map<String, Object> requirements, int topN) {
    int effectiveTopN = Math.max(1, Math.min(MAX_TOP_N, topN <= 0 ? DEFAULT_TOP_N : topN));
    return MatchingService.rank(candidateRepository.findAll(), requirements, effectiveTopN);
  }

  @Transactional(readOnly = true)
  public List<MatchRunEntity> listMatchRuns(UUID rfpId) {
    return matchRunRepository.findByRfpIdOrderByCreatedAtDesc(rfpId);
  }

  // ── Shortlists ───────────────────────────────────────────────────────

  @Transactional
  public ShortlistEntity createShortlist(UUID rfpId, List<UUID> candidateIds, String actor) {
    getRfp(rfpId);
    if (candidateIds == null || candidateIds.isEmpty()) {
      throw badRequest("candidateIds is required");
    }
    ShortlistEntity shortlist = new ShortlistEntity();
    shortlist.setRfpId(rfpId);
    shortlist.setCandidateIds(List.copyOf(candidateIds));
    shortlist.setCreatedBy(actor == null || actor.isBlank() ? "unknown" : actor);
    return shortlistRepository.save(shortlist);
  }

  @Transactional(readOnly = true)
  public List<ShortlistEntity> listShortlists(UUID rfpId) {
    return shortlistRepository.findByRfpIdOrderByCreatedAtDesc(rfpId);
  }

  // ── Extraction helpers (v1: naive) ───────────────────────────────────

  private static String extractLine(String text, String sectionKeywords) {
    for (String line : text.split("\\r?\\n")) {
      if (line.toLowerCase(Locale.ROOT).matches(".*\\b(" + sectionKeywords + ")\\b.*")
          && line.length() < 300
          && line.length() > 10) {
        return line.trim();
      }
    }
    return null;
  }

  private static String extractSeniority(String lower) {
    if (lower.contains("principal") || lower.contains("architect")) {
      return "principal";
    }
    if (lower.contains("senior") || lower.contains("sr.")) {
      return "senior";
    }
    if (lower.contains("lead")) {
      return "lead";
    }
    if (lower.contains("junior") || lower.contains("jr.")) {
      return "junior";
    }
    return null;
  }

  private static List<String> extractLanguages(String lower) {
    List<String> languages = new ArrayList<>();
    if (lower.contains("português") || lower.contains("portuguese") || lower.contains("pt-pt")) {
      languages.add("pt");
    }
    if (lower.contains("english") || lower.contains("inglês") || lower.contains("en-")) {
      languages.add("en");
    }
    if (lower.contains("spanish") || lower.contains("espanhol") || lower.contains("es-es")) {
      languages.add("es");
    }
    if (lower.contains("french") || lower.contains("francês") || lower.contains("fr-fr")) {
      languages.add("fr");
    }
    return languages;
  }

  private static String extractAvailability(String lower) {
    if (lower.contains("immediate") || lower.contains("imediato")) {
      return "immediate";
    }
    if (lower.contains("notice period") || lower.contains("pré-aviso")) {
      return "notice";
    }
    return null;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
