package com.crosshubber.portal.modules.registry.entrypointgroups;

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

/**
 * Entry point groups routes — mirrors {@code
 * portal/src/modules/entry-points/entry-point-groups.routes.ts}.
 */
@RestController
@RequestMapping("/api/registry/entry-point-groups")
public class EntryPointGroupsController {

  private final EntryPointGroupsService groupsService;

  public EntryPointGroupsController(EntryPointGroupsService groupsService) {
    this.groupsService = groupsService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String category) {
    List<Map<String, Object>> groups =
        groupsService.list(category).stream().map(EntryPointGroupsService::toOutput).toList();
    return ResponseEntity.ok(Map.of("groups", groups));
  }

  @PostMapping
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
    String error = groupsService.validate(body);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    EntryPointGroupEntity group = groupsService.upsert(body);
    return ResponseEntity.ok(Map.of("ok", true, "group", EntryPointGroupsService.toOutput(group)));
  }

  @PutMapping("/{key}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> update(@PathVariable String key, @RequestBody Map<String, Object> body) {
    Map<String, Object> input = new java.util.LinkedHashMap<>(body == null ? Map.of() : body);
    input.put("groupKey", key);
    String error = groupsService.validate(input);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    EntryPointGroupEntity group = groupsService.upsert(input);
    return ResponseEntity.ok(Map.of("ok", true, "group", EntryPointGroupsService.toOutput(group)));
  }

  @DeleteMapping("/{key}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> remove(@PathVariable String key) {
    return ResponseEntity.ok(Map.of("ok", groupsService.remove(key)));
  }

  @PostMapping("/reorder")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> reorder(@RequestBody Map<String, Object> body) {
    Object keys = body == null ? null : body.get("keys");
    if (!(keys instanceof List<?> list) || list.stream().anyMatch(k -> !(k instanceof String))) {
      return ResponseEntity.badRequest().body(Map.of("error", "keys must be an array of strings"));
    }
    @SuppressWarnings("unchecked")
    List<String> groupKeys = (List<String>) list;
    groupsService.reorder(groupKeys);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
