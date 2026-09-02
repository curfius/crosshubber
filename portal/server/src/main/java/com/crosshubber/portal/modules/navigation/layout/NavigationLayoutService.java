package com.crosshubber.portal.modules.navigation.layout;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Portal Navigation layout (instance singleton) — mirrors the layout part of {@code
 * portal/src/modules/navigation/navigation.repository.ts}.
 */
@Service
public class NavigationLayoutService {

  private final NavigationLayoutRepository repo;
  private final EntryPointRepository entryPointRepo;
  private final ObjectMapper objectMapper;

  public NavigationLayoutService(
      NavigationLayoutRepository repo,
      EntryPointRepository entryPointRepo,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.entryPointRepo = entryPointRepo;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Map<String, Object> getLayout() {
    Map<String, Object> raw = getLayoutRaw();
    if (raw != null) {
      return raw;
    }
    return NavigationValidationService.buildDefaultLayout(entryPointRepo.findAll());
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getLayoutRaw() {
    return repo.findById(1).map(e -> parseJson(e.getSettings())).orElse(null);
  }

  @Transactional
  public void saveLayout(JsonNode layout) {
    NavigationLayoutEntity entity =
        repo.findById(1)
            .orElseGet(
                () -> {
                  NavigationLayoutEntity created = new NavigationLayoutEntity();
                  created.setId(1);
                  return created;
                });
    entity.setSettings(writeJson(layout));
    repo.save(entity);
  }

  /** Returns the set of known app refs for layout validation. */
  @Transactional(readOnly = true)
  public Set<String> knownAppRefs() {
    return entryPointRepo.findAll().stream()
        .map(NavigationValidationService::entryPointRef)
        .collect(Collectors.toSet());
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode node = objectMapper.readTree(raw);
      return objectMapper.convertValue(node, Map.class);
    } catch (Exception e) {
      return null;
    }
  }

  private String writeJson(JsonNode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("navigation layout serialization failed", e);
    }
  }
}
