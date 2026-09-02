package com.crosshubber.portal.workspaces;

import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * Workspace routes â€” mirrors {@code portal/src/modules/workspaces/workspaces.routes.ts} +
 * service.ts: per-user CRUD with name-conflict retry ("name (n)").
 */
@RestController
public class WorkspacesController {

  private static final int MAX_NAME_ATTEMPTS = 100;

  private final WorkspaceRepository repo;
  private final ObjectMapper objectMapper;

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
    return ResponseEntity.internalServerError().body(Map.of("error", "could not find unique name"));
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
    entity.setDescription(body.get("description") instanceof String s ? s : "");
    entity.setLayout(body.get("layout") != null ? writeJson(body.get("layout")) : null);
    entity.setGroups(body.get("groups") != null ? writeJson(body.get("groups")) : "{}");
    entity.setFocusedGroupId(body.get("focusedGroupId") instanceof String s ? s : null);
    entity.setHideSingleTabToolbar(Boolean.TRUE.equals(body.get("hideSingleTabToolbar")));
    entity.setLocked(Boolean.TRUE.equals(body.get("locked")));
    entity.setColor(body.get("color") instanceof String s ? s : "");
    entity.setStatus(body.get("status") instanceof String s ? s : "");

    String finalName = name;
    for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
      try {
        entity.setName(finalName);
        repo.save(entity);
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "name", finalName));
      } catch (DataIntegrityViolationException e) {
        finalName = name + " (" + (attempt + 2) + ")";
      }
    }
    return ResponseEntity.internalServerError().body(Map.of("error", "could not find unique name"));
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
