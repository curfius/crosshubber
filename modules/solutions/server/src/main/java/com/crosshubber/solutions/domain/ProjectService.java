package com.crosshubber.solutions.domain;

import java.math.BigDecimal;
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

import com.crosshubber.solutions.docs.DocumentSource;

/**
 * Project delivery domain service: workflow transitions with stage-gate validation, immutable stage
 * history, document links with per-doc RAG opt-in, notes, and doc search (AI plan G2
 * fetch-on-demand + truncate, module-internal).
 */
@Service
public class ProjectService {

  private static final int MAX_SNIPPETS = 5;
  private static final int SNIPPET_LENGTH = 400;

  private final ClientRepository clientRepository;
  private final ProjectRepository projectRepository;
  private final StageEventRepository stageEventRepository;
  private final DocumentLinkRepository documentLinkRepository;
  private final NoteRepository noteRepository;
  private final DocumentSource documentSource;

  public ProjectService(
      ClientRepository clientRepository,
      ProjectRepository projectRepository,
      StageEventRepository stageEventRepository,
      DocumentLinkRepository documentLinkRepository,
      NoteRepository noteRepository,
      DocumentSource documentSource) {
    this.clientRepository = clientRepository;
    this.projectRepository = projectRepository;
    this.stageEventRepository = stageEventRepository;
    this.documentLinkRepository = documentLinkRepository;
    this.noteRepository = noteRepository;
    this.documentSource = documentSource;
  }

  // ── Clients ──────────────────────────────────────────────────────────

  @Transactional
  public ClientEntity createClient(
      String name, String industry, String accountOwner, String contacts) {
    if (name == null || name.isBlank()) {
      throw badRequest("client name is required");
    }
    if (clientRepository.findByName(name.trim()).isPresent()) {
      throw badRequest("client \"" + name.trim() + "\" already exists");
    }
    ClientEntity client = new ClientEntity();
    client.setName(name.trim());
    client.setIndustry(blankToNull(industry));
    client.setAccountOwner(blankToNull(accountOwner));
    client.setContacts(blankToNull(contacts));
    return clientRepository.save(client);
  }

  @Transactional(readOnly = true)
  public List<ClientEntity> listClients() {
    return clientRepository.findByOrderByNameAsc();
  }

  // ── Projects ─────────────────────────────────────────────────────────

  @Transactional
  public ProjectEntity createProject(UUID clientId, String name, String owner) {
    ClientEntity client =
        clientRepository
            .findById(clientId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "client not found"));
    if (name == null || name.isBlank()) {
      throw badRequest("project name is required");
    }
    ProjectEntity project = new ProjectEntity();
    project.setClient(client);
    project.setName(name.trim());
    project.setOwner(blankToNull(owner));
    project.setStage(Stage.LEAD);
    ProjectEntity saved = projectRepository.save(project);
    recordEvent(saved, null, Stage.LEAD, actor(owner), null);
    return saved;
  }

  @Transactional(readOnly = true)
  public List<ProjectEntity> listProjects(Stage stage, String health, UUID clientId) {
    if (stage != null) {
      return projectRepository.findByStage(stage);
    }
    if (health != null && !health.isBlank()) {
      return projectRepository.findByHealth(health);
    }
    if (clientId != null) {
      return projectRepository.findByClientId(clientId);
    }
    return projectRepository.findAll();
  }

  @Transactional(readOnly = true)
  public ProjectEntity getProject(UUID id) {
    return projectRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
  }

  /**
   * Moves a project to {@code toStage} — validates the transition, merges the optional {@code
   * stageData} patch, then enforces the target stage's gate fields. Terminal stages and illegal
   * transitions are rejected (409/422); accepted transitions write the audit event.
   */
  @Transactional
  public ProjectEntity transition(
      UUID projectId,
      String toStageRaw,
      String actor,
      String note,
      Map<String, Object> stageDataPatch) {
    Stage target = Stage.fromString(toStageRaw);
    ProjectEntity project = getProject(projectId);
    Stage current = project.getStage();
    if (!current.canTransitionTo(target)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "illegal transition " + current.value() + " -> " + target.value());
    }
    if (stageDataPatch != null && !stageDataPatch.isEmpty()) {
      Map<String, Object> merged = new HashMap<>(project.getStageData());
      merged.putAll(stageDataPatch);
      project.setStageData(merged);
    }
    List<String> missing = missingGateFields(project, target);
    if (!missing.isEmpty()) {
      throw badRequest(
          "stage " + target.value() + " requires fields: " + String.join(", ", missing));
    }
    if (target == Stage.IMPLEMENTATION
        && project.getHealth() != null
        && !Stage.HEALTH_VALUES.contains(project.getHealth())) {
      throw badRequest("health must be one of " + Stage.HEALTH_VALUES);
    }
    project.setStage(target);
    if (target == Stage.WON && project.getStartedAt() == null) {
      project.setStartedAt(java.time.Instant.now());
      Object budget = project.getStageData().get("budget");
      if (budget instanceof Number n) {
        project.setBudget(BigDecimal.valueOf(n.doubleValue()));
      }
    }
    if (target == Stage.IMPLEMENTATION) {
      Object health = project.getStageData().get("health");
      if (health instanceof String s && Stage.HEALTH_VALUES.contains(s)) {
        project.setHealth(s);
      }
    }
    if (target == Stage.CLOSED) {
      project.setClosedAt(java.time.Instant.now());
    }
    ProjectEntity saved = projectRepository.save(project);
    recordEvent(saved, current, target, actor(actor), note);
    return saved;
  }

  @Transactional(readOnly = true)
  public List<StageEventEntity> stageHistory(UUID projectId) {
    getProject(projectId);
    return stageEventRepository.findByProjectIdOrderByOccurredAtAsc(projectId);
  }

  // ── Document links + RAG search ──────────────────────────────────────

  @Transactional
  public DocumentLinkEntity addDocumentLink(
      UUID projectId, String documentRef, String title, boolean ragEnabled, String addedBy) {
    ProjectEntity project = getProject(projectId);
    if (documentRef == null || documentRef.isBlank()) {
      throw badRequest("documentRef is required");
    }
    if (title == null || title.isBlank()) {
      throw badRequest("title is required");
    }
    DocumentLinkEntity link = new DocumentLinkEntity();
    link.setProject(project);
    link.setDocumentRef(documentRef.trim());
    link.setTitle(title.trim());
    link.setRagEnabled(ragEnabled);
    link.setAddedBy(blankToNull(addedBy));
    return documentLinkRepository.save(link);
  }

  @Transactional
  public DocumentLinkEntity setRagEnabled(UUID projectId, long linkId, boolean ragEnabled) {
    getProject(projectId);
    DocumentLinkEntity link =
        documentLinkRepository
            .findById(linkId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document link not found"));
    if (!link.getProject().getId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document link not found");
    }
    link.setRagEnabled(ragEnabled);
    return documentLinkRepository.save(link);
  }

  @Transactional(readOnly = true)
  public List<DocumentLinkEntity> listDocumentLinks(UUID projectId) {
    getProject(projectId);
    return documentLinkRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
  }

  /**
   * Fetch-on-demand doc search (AI plan G2, module-internal): fetches only RAG-enabled links,
   * splits content into chunks, scores by query-term overlap and returns the top snippets with
   * citations. No vector store.
   */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> searchProjectDocs(UUID projectId, String query) {
    getProject(projectId);
    if (query == null || query.isBlank()) {
      throw badRequest("query is required");
    }
    List<String> terms = tokenize(query);
    List<Map<String, Object>> results = new ArrayList<>();
    for (DocumentLinkEntity link :
        documentLinkRepository.findByProjectIdAndRagEnabledTrue(projectId)) {
      String text;
      try {
        text = documentSource.fetchDocument(link.getDocumentRef()).text();
      } catch (Exception e) {
        Map<String, Object> errorRow = new HashMap<>();
        errorRow.put("documentRef", link.getDocumentRef());
        errorRow.put("title", link.getTitle());
        errorRow.put("error", "fetch failed: " + safeMessage(e));
        results.add(errorRow);
        continue;
      }
      for (String chunk : chunks(text, SNIPPET_LENGTH)) {
        long score = terms.stream().filter(chunk.toLowerCase(Locale.ROOT)::contains).count();
        if (score > 0) {
          Map<String, Object> row = new HashMap<>();
          row.put("documentRef", link.getDocumentRef());
          row.put("title", link.getTitle());
          row.put("snippet", chunk.trim());
          row.put("score", score);
          results.add(row);
        }
      }
    }
    results.sort(
        (a, b) ->
            Long.compare((long) b.getOrDefault("score", 0L), (long) a.getOrDefault("score", 0L)));
    return results.subList(0, Math.min(MAX_SNIPPETS, results.size()));
  }

  // ── Notes ────────────────────────────────────────────────────────────

  @Transactional
  public NoteEntity addNote(UUID projectId, String author, String body) {
    ProjectEntity project = getProject(projectId);
    if (body == null || body.isBlank()) {
      throw badRequest("body is required");
    }
    NoteEntity note = new NoteEntity();
    note.setProject(project);
    note.setAuthor(blankToNull(author) == null ? "unknown" : author.trim());
    note.setBody(body.trim());
    return noteRepository.save(note);
  }

  @Transactional(readOnly = true)
  public List<NoteEntity> listNotes(UUID projectId) {
    getProject(projectId);
    return noteRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
  }

  // ── Internals ────────────────────────────────────────────────────────

  private List<String> missingGateFields(ProjectEntity project, Stage target) {
    return Stage.missingGateFields(project.getStageData(), target);
  }

  private void recordEvent(ProjectEntity project, Stage from, Stage to, String actor, String note) {
    StageEventEntity event = new StageEventEntity();
    event.setProject(project);
    event.setFromStage(from == null ? "(created)" : from.value());
    event.setToStage(to.value());
    event.setActor(actor);
    event.setNote(note);
    stageEventRepository.save(event);
  }

  private static String actor(String actor) {
    return blankToNull(actor) == null ? "unknown" : actor.trim();
  }

  private static List<String> tokenize(String query) {
    List<String> terms = new ArrayList<>();
    for (String token : query.toLowerCase(Locale.ROOT).split("\\W+")) {
      if (token.length() >= 3) {
        terms.add(token);
      }
    }
    return terms;
  }

  private static List<String> chunks(String text, int length) {
    List<String> chunks = new ArrayList<>();
    String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    for (int i = 0; i < clean.length(); i += length) {
      chunks.add(clean.substring(i, Math.min(clean.length(), i + length)));
    }
    return chunks.isEmpty() ? List.of("") : chunks;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String blankToNull(String value) {
    return value != null && !value.isBlank() ? value.trim() : null;
  }

  private static String safeMessage(Exception e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
