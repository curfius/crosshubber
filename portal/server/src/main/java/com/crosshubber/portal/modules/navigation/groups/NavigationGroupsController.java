package com.crosshubber.portal.modules.navigation.groups;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** Navigation group routes (shell-nav sections). */
@RestController
@Validated
@RequestMapping("/api/navigation/groups")
public class NavigationGroupsController {

  private final NavigationGroupsService groupsService;

  public NavigationGroupsController(NavigationGroupsService groupsService) {
    this.groupsService = groupsService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      @RequestParam(required = false)
          @Pattern(
              regexp = "applications|settings|features|admin-settings|user-settings",
              message = "must be applications|settings|features|admin-settings|user-settings")
          String category) {
    List<NavigationGroupDto> groups =
        groupsService.list(category).stream().map(NavigationGroupsService::toOutput).toList();
    return ResponseEntity.ok(Map.of("groups", groups));
  }

  @PostMapping
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> create(@Valid @RequestBody NavigationGroupUpsertRequest body) {
    NavigationGroupEntity group = groupsService.upsert(body);
    return ResponseEntity.status(201)
        .body(Map.of("ok", true, "group", NavigationGroupsService.toOutput(group)));
  }

  @PutMapping("/{key}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> update(
      @PathVariable String key, @Valid @RequestBody NavigationGroupUpsertRequest body) {
    // Path wins over body: the group key is the resource identity.
    NavigationGroupUpsertRequest input =
        new NavigationGroupUpsertRequest(
            key,
            body.category(),
            body.name(),
            body.parentKey(),
            body.sortOrder(),
            body.icon(),
            body.roles());
    NavigationGroupEntity group = groupsService.upsert(input);
    return ResponseEntity.ok(Map.of("ok", true, "group", NavigationGroupsService.toOutput(group)));
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
    // Missing or empty keys → 200 {ok:true} (no-op)
    if (keys == null) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    if (!(keys instanceof List<?> list)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "keys: Invalid input: expected array, received undefined"));
    }
    if (list.isEmpty()) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    if (list.stream().anyMatch(k -> !(k instanceof String))) {
      return ResponseEntity.badRequest().body(Map.of("error", "keys must be an array of strings"));
    }
    @SuppressWarnings("unchecked")
    List<String> groupKeys = (List<String>) list;
    groupsService.reorder(groupKeys);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
