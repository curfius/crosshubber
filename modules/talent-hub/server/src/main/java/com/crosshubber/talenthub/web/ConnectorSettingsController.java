package com.crosshubber.talenthub.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.connectors.ConnectorCategory;
import com.crosshubber.talenthub.connectors.ConnectorRegistry;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Connector registry REST (P4.1/P4.3): per-category kinds + active kind listing and self-test
 * (admin-gated; masked config rides the /api/settings view).
 */
@RestController
public class ConnectorSettingsController {

  private final ConnectorRegistry registry;

  public ConnectorSettingsController(ConnectorRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/api/settings/connectors")
  public Map<String, Object> list() {
    ModuleRoles.requireAdmin();
    Map<String, Object> categories = new LinkedHashMap<>();
    for (ConnectorCategory category : ConnectorCategory.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("kinds", registry.kindsFor(category));
      var resolved = registry.resolve(category);
      row.put("kind", resolved == null ? null : resolved.kind());
      categories.put(category.key(), row);
    }
    return Map.of("categories", categories);
  }

  @PostMapping("/api/settings/connectors/{category}/test")
  public Map<String, Object> test(@PathVariable String category) {
    ModuleRoles.requireAdmin();
    ConnectorCategory connectorCategory = ConnectorCategory.fromKey(category);
    if (connectorCategory == null) {
      throw new IllegalArgumentException("unknown connector category: " + category);
    }
    return registry.test(connectorCategory);
  }
}
