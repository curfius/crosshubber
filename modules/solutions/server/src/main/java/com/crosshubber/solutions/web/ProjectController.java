package com.crosshubber.solutions.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.solutions.domain.ClientEntity;
import com.crosshubber.solutions.domain.DocumentLinkEntity;
import com.crosshubber.solutions.domain.NoteEntity;
import com.crosshubber.solutions.domain.ProjectEntity;
import com.crosshubber.solutions.domain.ProjectService;
import com.crosshubber.solutions.domain.Stage;
import com.crosshubber.solutions.security.AgentPrincipals;

/** Project delivery REST surface (consumed by the module UI via its agent-call token). */
@RestController
@RequestMapping("/api")
public class ProjectController {

  /** Transition request: target stage, optional stage-data patch and note. */
  public record TransitionRequest(String toStage, String note, Map<String, Object> stageData) {}

  public record CreateProjectRequest(UUID clientId, String name, String owner) {}

  public record CreateClientRequest(
      String name, String industry, String accountOwner, String contacts) {}

  public record DocumentLinkRequest(String documentRef, String title, boolean ragEnabled) {}

  public record RagToggleRequest(boolean ragEnabled) {}

  public record NoteRequest(String body) {}

  private final ProjectService service;

  public ProjectController(ProjectService service) {
    this.service = service;
  }

  // ── Clients ──────────────────────────────────────────────────────────

  @GetMapping("/clients")
  public Map<String, Object> listClients() {
    return Map.of("clients", service.listClients());
  }

  @PostMapping("/clients")
  public ResponseEntity<ClientEntity> createClient(@RequestBody CreateClientRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.createClient(
                body.name(), body.industry(), body.accountOwner(), body.contacts()));
  }

  // ── Projects ─────────────────────────────────────────────────────────

  @GetMapping("/projects")
  public Map<String, Object> listProjects(
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String health,
      @RequestParam(required = false) UUID clientId) {
    Stage stageFilter = stage == null ? null : Stage.fromString(stage);
    List<ProjectEntity> projects = service.listProjects(stageFilter, health, clientId);
    return Map.of("projects", projects);
  }

  @PostMapping("/projects")
  public ResponseEntity<ProjectEntity> createProject(@RequestBody CreateProjectRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createProject(body.clientId(), body.name(), body.owner()));
  }

  @GetMapping("/projects/{id}")
  public ProjectEntity getProject(@PathVariable UUID id) {
    return service.getProject(id);
  }

  @PostMapping("/projects/{id}/transitions")
  public ProjectEntity transition(@PathVariable UUID id, @RequestBody TransitionRequest body) {
    return service.transition(
        id, body.toStage(), AgentPrincipals.currentName(), body.note(), body.stageData());
  }

  @GetMapping("/projects/{id}/history")
  public Map<String, Object> stageHistory(@PathVariable UUID id) {
    return Map.of("events", service.stageHistory(id));
  }

  // ── Documents ────────────────────────────────────────────────────────

  @PostMapping("/projects/{id}/documents")
  public ResponseEntity<DocumentLinkEntity> addDocument(
      @PathVariable UUID id, @RequestBody DocumentLinkRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.addDocumentLink(
                id,
                body.documentRef(),
                body.title(),
                body.ragEnabled(),
                AgentPrincipals.currentName()));
  }

  @PatchMapping("/projects/{id}/documents/{linkId}")
  public DocumentLinkEntity toggleRag(
      @PathVariable UUID id, @PathVariable long linkId, @RequestBody RagToggleRequest body) {
    return service.setRagEnabled(id, linkId, body.ragEnabled());
  }

  @GetMapping("/projects/{id}/documents")
  public Map<String, Object> listDocuments(@PathVariable UUID id) {
    return Map.of("documents", service.listDocumentLinks(id));
  }

  // ── Notes ────────────────────────────────────────────────────────────

  @PostMapping("/projects/{id}/notes")
  public ResponseEntity<NoteEntity> addNote(@PathVariable UUID id, @RequestBody NoteRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.addNote(id, AgentPrincipals.currentName(), body.body()));
  }

  @GetMapping("/projects/{id}/notes")
  public Map<String, Object> listNotes(@PathVariable UUID id) {
    return Map.of("notes", service.listNotes(id));
  }
}
