package com.crosshubber.portal.modules.registry.entrypoints;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.registry.dto.EntryPointDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointUpsertRequest;

import jakarta.validation.Valid;

/** Entry points routes. */
@RestController
@RequestMapping("/api/registry/entry-points")
public class EntryPointsController {

  private final EntryPointsService entryPointsService;

  public EntryPointsController(EntryPointsService entryPointsService) {
    this.entryPointsService = entryPointsService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      @RequestParam(required = false) String moduleKey) {
    List<EntryPointDto> entryPoints =
        entryPointsService.list(moduleKey).stream().map(EntryPointsService::toOutput).toList();
    return ResponseEntity.ok(Map.of("entryPoints", entryPoints));
  }

  @PostMapping
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> create(@Valid @RequestBody EntryPointUpsertRequest body) {
    String error = entryPointsService.validate(body);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    if ("embedded".equals(body.type())
        && body.loadPath() != null
        && !entryPointsService.validateLoadPath(body.loadPath())) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "loadPath \""
                      + body.loadPath()
                      + "\" is not registered."
                      + " Add it to portal.known_load_paths first."));
    }
    EntryPointEntity entryPoint = entryPointsService.upsert(body);
    return ResponseEntity.status(201)
        .body(Map.of("ok", true, "entryPoint", EntryPointsService.toOutput(entryPoint)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> update(
      @PathVariable long id, @Valid @RequestBody EntryPointUpsertRequest body) {
    String error = entryPointsService.validate(body);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    if ("embedded".equals(body.type())
        && body.loadPath() != null
        && !entryPointsService.validateLoadPath(body.loadPath())) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "loadPath \"" + body.loadPath() + "\" is not registered."));
    }
    EntryPointEntity entryPoint = entryPointsService.upsert(body);
    return ResponseEntity.ok(
        Map.of("ok", true, "entryPoint", EntryPointsService.toOutput(entryPoint)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> remove(@PathVariable long id) {
    return ResponseEntity.ok(Map.of("ok", entryPointsService.remove(id)));
  }

  @PostMapping("/reorder")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> reorder(@RequestBody Map<String, Object> body) {
    Object idsRaw = body == null ? null : body.get("ids");
    // Missing or empty ids → 200 {ok:true} (no-op)
    if (idsRaw == null) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    if (!(idsRaw instanceof List<?> ids)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "ids: Invalid input: expected array, received undefined"));
    }
    if (ids.isEmpty()) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    if (ids.stream().anyMatch(i -> !(i instanceof Number))) {
      return ResponseEntity.badRequest().body(Map.of("error", "ids must be an array of numbers"));
    }
    List<Long> idList = ids.stream().map(i -> ((Number) i).longValue()).toList();
    entryPointsService.reorder(idList);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
