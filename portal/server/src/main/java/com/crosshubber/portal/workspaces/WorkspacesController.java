package com.crosshubber.portal.workspaces;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * Workspace routes â€” mirrors {@code portal/src/modules/workspaces/workspaces.routes.ts} +
 * service.ts: per-user CRUD with name-conflict retry ("name (n)").
 */
@RestController
public class WorkspacesController {

  private static final int MAX_NAME_ATTEMPTS = 100;

  private final WorkspaceRepository repo;
  private final ObjectMapper objectMapper;

  @PersistenceContext private EntityManager em;

  public WorkspacesController(WorkspaceRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/api/workspaces")
  public Map<String, Object> list(@AuthenticationPrincipal PortalUser user) {
    List<Map<String, Object>> workspaces =
        repo.findByUserId(user.sub()).stream()
            .sorted((a, b) -> b.getSavedAt().compareTo(a.getSavedAt()))
            .map(WorkspacesController::listItem)
            .toList();
    return Map.of("workspaces", workspaces);
  }

  @GetMapping("/api/workspaces/{name}")
  public ResponseEntity<?> get(
      @AuthenticationPrincipal PortalUser user, @PathVariable String name) {
    var found = repo.findByUserIdAndName(user.sub(), name);
    if (found.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    return ResponseEntity.ok(detail(found.get()));
  }

  @PostMapping("/api/workspaces")
  public ResponseEntity<?> create(
      @AuthenticationPrincipal PortalUser user,
      @RequestBody(required = false) Map<String, Object> body) {
    String name =
        body != null && body.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
    if (name == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    String description = body.get("description") instanceof String s ? s : "";
    String layout = body.get("layout") != null ? writeJson(body.get("layout")) : null;
    String groups = body.get("groups") != null ? writeJson(body.get("groups")) : "{}";
    String focusedGroupId = body.get("focusedGroupId") instanceof String s ? s : null;
    boolean hideSingleTabToolbar = Boolean.TRUE.equals(body.get("hideSingleTabToolbar"));
    boolean locked = Boolean.TRUE.equals(body.get("locked"));
    String color = body.get("color") instanceof String s ? s : "";
    String status = body.get("status") instanceof String s ? s : "";

    String finalName = name;
    for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
      try {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setUserId(user.sub());
        entity.setName(finalName);
        entity.setDescription(description);
        entity.setLayout(layout);
        entity.setGroups(groups);
        entity.setFocusedGroupId(focusedGroupId);
        entity.setHideSingleTabToolbar(hideSingleTabToolbar);
        entity.setLocked(locked);
        entity.setColor(color);
        entity.setStatus(status);
        entity.setId(UUID.randomUUID());
        repo.save(entity);
        return ResponseEntity.ok(
            Map.of("ok", true, "id", entity.getId().toString(), "name", finalName));
      } catch (DataIntegrityViolationException e) {
        finalName = name + " (" + (attempt + 2) + ")";
      }
    }
    return ResponseEntity.internalServerError().body(Map.of("error", "internal server error"));
  }

  @PutMapping("/api/workspaces/{id}")
  public ResponseEntity<?> update(
      @AuthenticationPrincipal PortalUser user,
      @PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    String name =
        body != null && body.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
    if (name == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    WorkspaceEntity entity =
        repo.findByUserId(user.sub()).stream()
            .filter(w -> w.getId().toString().equals(id))
            .findFirst()
            .orElse(null);
    if (entity == null) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }

    // Snapshot of the pre-request row — restored after a failed rename attempt so the retry
    // loop always starts from the original state (Node's failed UPDATE leaves the row as-is).
    WorkspaceEntity snapshot = copyRow(entity);
    WorkspaceEntity updated = copyRow(entity);
    updated.setDescription(body.get("description") instanceof String s ? s : "");
    updated.setLayout(body.get("layout") != null ? writeJson(body.get("layout")) : null);
    updated.setGroups(body.get("groups") != null ? writeJson(body.get("groups")) : "{}");
    updated.setFocusedGroupId(body.get("focusedGroupId") instanceof String s ? s : null);
    updated.setHideSingleTabToolbar(Boolean.TRUE.equals(body.get("hideSingleTabToolbar")));
    updated.setLocked(Boolean.TRUE.equals(body.get("locked")));
    updated.setColor(body.get("color") instanceof String s ? s : "");
    updated.setStatus(body.get("status") instanceof String s ? s : "");

    String finalName = name;
    for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
      try {
        // name is part of the composite PK — a rename is delete-old-row + insert-new-row with
        // the same UUID id (in-place PK mutation would merge into a duplicate row). The fresh
        // saved_at also covers the plain-save case (Node: saved_at = now() on every update).
        WorkspaceEntity replacement = copyRow(updated);
        replacement.setName(finalName);
        replacement.setSavedAt(Instant.now());
        em.remove(em.contains(entity) ? entity : em.merge(entity));
        em.flush();
        em.persist(replacement);
        em.flush();
        // Node responds with the REQUESTED (trimmed) name even when a "name (2)" suffix was
        // stored (workspaces.routes.ts:47).
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "name", name));
      } catch (DataIntegrityViolationException | PersistenceException e) {
        if (!isDuplicateKey(e)) {
          throw e;
        }
        em.clear();
        em.persist(copyRow(snapshot));
        em.flush();
        finalName = name + " (" + (attempt + 2) + ")";
      }
    }
    // Retry exhaustion — Node throws and errors.ts returns the generic 500 body.
    return ResponseEntity.internalServerError().body(Map.of("error", "internal server error"));
  }

  @DeleteMapping("/api/workspaces/{name}")
  @Transactional
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal PortalUser user, @PathVariable String name) {
    var found = repo.findByUserIdAndName(user.sub(), name);
    boolean deleted = found.isPresent();
    found.ifPresent(repo::delete);
    return ResponseEntity.ok(Map.of("ok", deleted));
  }

  // ── DTOs (camelCase, mirroring workspaces.service.ts) ──

  /** Field-by-field copy (identity fields included) for rename replacements and restores. */
  private static WorkspaceEntity copyRow(WorkspaceEntity source) {
    WorkspaceEntity copy = new WorkspaceEntity();
    copy.setUserId(source.getUserId());
    copy.setName(source.getName());
    copy.setId(source.getId());
    copy.setDescription(source.getDescription());
    copy.setLayout(source.getLayout());
    copy.setGroups(source.getGroups());
    copy.setFocusedGroupId(source.getFocusedGroupId());
    copy.setHideSingleTabToolbar(source.getHideSingleTabToolbar());
    copy.setLocked(source.getLocked());
    copy.setColor(source.getColor());
    copy.setStatus(source.getStatus());
    copy.setSavedAt(source.getSavedAt());
    return copy;
  }

  /** Mirrors Node's {@code /duplicate key/i.test(String(err))} retry condition. */
  private static boolean isDuplicateKey(Throwable error) {
    while (error != null) {
      String message = error.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate key")) {
        return true;
      }
      error = error.getCause();
    }
    return false;
  }

  private static Map<String, Object> listItem(WorkspaceEntity w) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", w.getId().toString());
    out.put("name", w.getName());
    out.put("description", w.getDescription() != null ? w.getDescription() : "");
    out.put("color", w.getColor() != null ? w.getColor() : "");
    out.put("status", w.getStatus() != null ? w.getStatus() : "");
    out.put("savedAt", w.getSavedAt().toEpochMilli());
    return out;
  }

  private Map<String, Object> detail(WorkspaceEntity w) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", w.getId().toString());
    out.put("name", w.getName());
    out.put("description", w.getDescription() != null ? w.getDescription() : "");
    out.put("layout", parseJsonSafe(w.getLayout()));
    out.put("groups", parseJsonSafe(w.getGroups()));
    out.put("focusedGroupId", w.getFocusedGroupId());
    out.put("hideSingleTabToolbar", Boolean.TRUE.equals(w.getHideSingleTabToolbar()));
    out.put("locked", Boolean.TRUE.equals(w.getLocked()));
    out.put("color", w.getColor() != null ? w.getColor() : "");
    out.put("status", w.getStatus() != null ? w.getStatus() : "");
    out.put("savedAt", w.getSavedAt().toEpochMilli());
    return out;
  }

  private Object parseJsonSafe(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      return null;
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("workspace serialization failed", e);
    }
  }
}
