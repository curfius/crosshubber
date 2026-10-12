package com.crosshubber.talenthub.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Talent pool (P2.6): directory with filters, detail with computed process history, manual add,
 * patch, rules-based enrichment (staleness framework). Writes are recruiter-or-better (controller);
 * reads open to all staffing roles.
 */
@Service
public class CandidateService {

  static final long STALE_AFTER_DAYS = 30;

  private static final Set<String> KINDS = Set.of("internal", "external");

  public record CandidateDto(
      UUID id,
      String sourceRef,
      String name,
      String headline,
      List<String> skills,
      String seniority,
      List<String> languages,
      String availability,
      String status,
      String kind,
      Map<String, Object> contact,
      Map<String, Object> assignment,
      Map<String, Object> expectations,
      Map<String, Object> consent,
      String lastEnrichedAt,
      boolean stale,
      String notes) {}

  /** Computed per-candidate history (every position/RFP they went through). */
  public record ProcessHistoryEntry(
      UUID processId,
      UUID rfpId,
      String rfpTitle,
      UUID positionId,
      String positionTitle,
      String stage,
      String createdAt) {}

  public record CandidateDetailDto(CandidateDto candidate, List<ProcessHistoryEntry> history) {}

  public record CandidateCreateRequest(
      String name,
      String headline,
      String seniority,
      List<String> skills,
      List<String> languages,
      String availability,
      String kind,
      Map<String, Object> contact,
      Map<String, Object> expectations,
      Map<String, Object> assignment,
      Map<String, Object> consent) {}

  public record CandidatePatchRequest(
      Integer version,
      String headline,
      String seniority,
      List<String> skills,
      List<String> languages,
      String availability,
      Map<String, Object> contact,
      Map<String, Object> expectations,
      Map<String, Object> assignment,
      Map<String, Object> consent,
      String notes) {}

  /** Fetches a CV by ref through the docsource port, then stores the parsed profile. */
  public CandidateDto scan(String documentRef, String title, String actor) {
    if (documentRef == null || documentRef.isBlank()) {
      throw new IllegalArgumentException("documentRef is required");
    }
    String text;
    try {
      text = documentSource.fetchDocument(documentRef).text();
    } catch (Exception e) {
      log.warn("[candidates] scan of {} failed: {}", documentRef, e.getMessage());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found: " + documentRef);
    }
    return ingest(documentRef, title, text);
  }

  /** Re-scanning the same source ref marks previous profiles STALE and adds a fresh parse. */
  public CandidateDto ingest(String documentRef, String title, String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("document text is empty for " + documentRef);
    }
    candidateRepository
        .findBySourceRef(documentRef)
        .forEach(
            staleOne -> {
              staleOne.setStatus(CandidateProfileEntity.ProfileStatus.STALE);
              candidateRepository.save(staleOne);
            });
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    CandidateProfileEntity profile = new CandidateProfileEntity();
    profile.setSourceRef(documentRef);
    profile.setName(title != null && !title.isBlank() ? title.trim() : documentRef);
    profile.setHeadline(extractLine(text, "summary|profile|about"));
    profile.setSkills(SKILL_DICTIONARY.stream().filter(lower::contains).toList());
    profile.setSeniority(extractSeniority(lower));
    profile.setLanguages(extractLanguages(lower));
    profile.setAvailability(extractAvailability(lower));
    profile.setStatus(CandidateProfileEntity.ProfileStatus.PARSED);
    profile.setLastEnrichedAt(Instant.now());
    return toDto(candidateRepository.save(profile));
  }

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(CandidateService.class);

  /** Naive skill vocabulary for scan extraction (LLM-assisted extraction is a later slice). */
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

  private static String extractLine(String text, String sectionKeywords) {
    for (String line : text.split("\\r?\\n")) {
      if (line.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(" + sectionKeywords + ")\\b.*")
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
    if (lower.contains("english") || lower.contains("inglês")) {
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

  private final CandidateProfileRepository candidateRepository;
  private final CandidateProcessRepository processRepository;
  private final PositionRepository positionRepository;
  private final RfpRepository rfpRepository;
  private final com.crosshubber.talenthub.docs.DocumentSource documentSource;

  public CandidateService(
      CandidateProfileRepository candidateRepository,
      CandidateProcessRepository processRepository,
      PositionRepository positionRepository,
      RfpRepository rfpRepository,
      com.crosshubber.talenthub.docs.DocumentSource documentSource) {
    this.candidateRepository = candidateRepository;
    this.processRepository = processRepository;
    this.positionRepository = positionRepository;
    this.rfpRepository = rfpRepository;
    this.documentSource = documentSource;
  }

  @Transactional(readOnly = true)
  public List<CandidateDto> list(
      String query, String kind, String availability, boolean staleOnly) {
    return candidateRepository.findAll().stream()
        .filter(c -> matchesQuery(c, query))
        .filter(c -> kind == null || kind.isBlank() || kind.equals(c.getKind()))
        .filter(
            c ->
                availability == null
                    || "any".equalsIgnoreCase(availability)
                    || ("now".equalsIgnoreCase(availability)
                        == (c.getAvailability() != null
                            && c.getAvailability().toLowerCase().contains("immediate"))))
        .filter(c -> !staleOnly || isStale(c))
        .sorted(
            java.util.Comparator.comparing(
                CandidateProfileEntity::getName, String.CASE_INSENSITIVE_ORDER))
        .map(CandidateService::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public CandidateDetailDto detail(UUID id) {
    CandidateProfileEntity candidate = load(id);
    List<CandidateProcessEntity> processes =
        processRepository.findByCandidateIdOrderByCreatedAtDesc(id);
    Map<UUID, PositionEntity> positions =
        positionRepository
            .findAllById(processes.stream().map(CandidateProcessEntity::getPositionId).toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(PositionEntity::getId, p -> p, (a, b) -> a));
    Map<UUID, RfpEntity> rfps =
        rfpRepository
            .findAllById(
                positions.values().stream().map(PositionEntity::getRfpId).distinct().toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(RfpEntity::getId, r -> r, (a, b) -> a));
    List<ProcessHistoryEntry> history =
        processes.stream()
            .map(
                process -> {
                  PositionEntity position = positions.get(process.getPositionId());
                  RfpEntity rfp = position == null ? null : rfps.get(position.getRfpId());
                  return new ProcessHistoryEntry(
                      process.getId(),
                      position == null ? null : position.getRfpId(),
                      rfp == null ? null : rfp.getTitle(),
                      process.getPositionId(),
                      position == null ? null : position.getTitle(),
                      process.getStage().value(),
                      process.getCreatedAt() == null ? null : process.getCreatedAt().toString());
                })
            .toList();
    return new CandidateDetailDto(toDto(candidate), history);
  }

  @Transactional
  public CandidateDto create(CandidateCreateRequest request, String actor) {
    if (request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (request.name().length() > 200) {
      throw new IllegalArgumentException("name must be at most 200 chars");
    }
    String kind = request.kind() == null ? "external" : request.kind();
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("kind must be internal or external");
    }
    CandidateProfileEntity entity = new CandidateProfileEntity();
    entity.setName(request.name().trim());
    entity.setSourceRef("manual:" + UUID.randomUUID());
    entity.setHeadline(request.headline());
    entity.setSeniority(request.seniority());
    entity.setSkills(new ArrayList<>(request.skills() == null ? List.of() : request.skills()));
    entity.setLanguages(
        new ArrayList<>(request.languages() == null ? List.of() : request.languages()));
    entity.setAvailability(request.availability());
    entity.setKind(kind);
    entity.setStatus(CandidateProfileEntity.ProfileStatus.PARSED);
    if (request.contact() != null) {
      entity.setContact(new HashMap<>(request.contact()));
    }
    if (request.expectations() != null) {
      entity.setExpectations(request.expectations());
    }
    if (request.assignment() != null) {
      entity.setAssignment(request.assignment());
    }
    if (request.consent() != null) {
      entity.setConsent(request.consent());
    }
    if (request.skills() != null && !request.skills().isEmpty()) {
      entity.setLastEnrichedAt(Instant.now());
    }
    return toDto(candidateRepository.save(entity));
  }

  @Transactional
  public CandidateDto patch(UUID id, CandidatePatchRequest request, String actor) {
    CandidateProfileEntity entity = load(id);
    if (request == null) {
      throw new IllegalArgumentException("body is required");
    }
    if (request.version() != null && !request.version().equals(entity.getVersion())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "version conflict; reload and retry");
    }
    if (request.headline() != null) {
      entity.setHeadline(request.headline());
    }
    if (request.seniority() != null) {
      entity.setSeniority(request.seniority());
    }
    if (request.skills() != null) {
      entity.setSkills(new ArrayList<>(request.skills()));
    }
    if (request.languages() != null) {
      entity.setLanguages(new ArrayList<>(request.languages()));
    }
    if (request.availability() != null) {
      entity.setAvailability(request.availability());
    }
    if (request.contact() != null) {
      entity.setContact(new HashMap<>(request.contact()));
    }
    if (request.expectations() != null) {
      entity.setExpectations(request.expectations());
    }
    if (request.assignment() != null) {
      entity.setAssignment(request.assignment());
    }
    if (request.consent() != null) {
      entity.setConsent(request.consent());
    }
    if (request.notes() != null) {
      entity.setNotes(request.notes());
    }
    return toDto(candidateRepository.save(entity));
  }

  /** Rules-based re-pass (P2.6): marks fresh; LLM-assisted extraction arrives later. */
  @Transactional
  public CandidateDto enrich(UUID id, String actor) {
    CandidateProfileEntity entity = load(id);
    entity.setLastEnrichedAt(Instant.now());
    if (entity.getStatus() == CandidateProfileEntity.ProfileStatus.STALE) {
      entity.setStatus(CandidateProfileEntity.ProfileStatus.PARSED);
    }
    return toDto(candidateRepository.save(entity));
  }

  private CandidateProfileEntity load(UUID id) {
    return candidateRepository
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "candidate not found"));
  }

  private static boolean matchesQuery(CandidateProfileEntity c, String query) {
    if (query == null || query.isBlank()) {
      return true;
    }
    String q = query.toLowerCase();
    if (c.getName() != null && c.getName().toLowerCase().contains(q)) {
      return true;
    }
    if (c.getHeadline() != null && c.getHeadline().toLowerCase().contains(q)) {
      return true;
    }
    for (String skill : c.getSkills()) {
      if (skill.toLowerCase().contains(q)) {
        return true;
      }
    }
    return false;
  }

  static boolean isStale(CandidateProfileEntity c) {
    if (c.getLastEnrichedAt() == null) {
      return true;
    }
    return c.getLastEnrichedAt().isBefore(Instant.now().minus(Duration.ofDays(STALE_AFTER_DAYS)));
  }

  static CandidateDto toDto(CandidateProfileEntity e) {
    Map<String, Object> contact = e.getContact() == null ? new LinkedHashMap<>() : e.getContact();
    return new CandidateDto(
        e.getId(),
        e.getSourceRef(),
        e.getName(),
        e.getHeadline(),
        e.getSkills(),
        e.getSeniority(),
        e.getLanguages(),
        e.getAvailability(),
        e.getStatus().name().toLowerCase(java.util.Locale.ROOT),
        e.getKind(),
        new LinkedHashMap<>(contact),
        e.getAssignment(),
        e.getExpectations(),
        e.getConsent(),
        e.getLastEnrichedAt() == null ? null : e.getLastEnrichedAt().toString(),
        isStale(e),
        e.getNotes());
  }
}
