package com.crosshubber.portal.modules.registry.modules;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Module registry routes — mirrors {@code portal/src/modules/modules/modules.routes.ts}. */
@RestController
@RequestMapping("/api/registry/modules")
public class ModulesController {

  private final ModulesService modulesService;

  public ModulesController(ModulesService modulesService) {
    this.modulesService = modulesService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list() {
    return ResponseEntity.ok(
        Map.of(
            "modules", modulesService.list(true).stream().map(modulesService::toOutput).toList()));
  }

  @PostMapping
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> upsert(@RequestBody Map<String, Object> body) {
    ModuleEntity module = modulesService.upsert(body);
    return ResponseEntity.ok(Map.of("ok", true, "module", modulesService.toOutput(module)));
  }

  @PatchMapping("/{key}/active")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> setActive(
      @PathVariable String key, @RequestBody Map<String, Object> body) {
    Object active = body == null ? null : body.get("active");
    if (!(active instanceof Boolean b)) {
      return ResponseEntity.badRequest().body(Map.of("error", "active must be a boolean"));
    }
    ModuleEntity module = modulesService.setActive(key, b);
    return ResponseEntity.ok(Map.of("ok", true, "module", modulesService.toOutput(module)));
  }

  @DeleteMapping("/{key}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> remove(@PathVariable String key) {
    boolean deleted = modulesService.remove(key);
    return ResponseEntity.ok(Map.of("ok", deleted));
  }
}
