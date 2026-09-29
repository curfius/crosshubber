package com.crosshubber.portal.modules.navigation.layout;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;

import tools.jackson.databind.JsonNode;

/** Portal Navigation layout (instance singleton). */
@Service
public class NavigationLayoutService {

  private final NavigationLayoutRepository repo;
  private final ModuleContentRepository contentRepo;
  private final JsonUtils jsonUtils;

  public NavigationLayoutService(
      NavigationLayoutRepository repo, ModuleContentRepository contentRepo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.contentRepo = contentRepo;
    this.jsonUtils = jsonUtils;
  }

  @Transactional
  public Map<String, Object> getLayout() {
    Map<String, Object> raw = getLayoutRaw();
    if (raw != null) {
      return raw;
    }
    return NavigationValidationService.buildDefaultLayout(contentRepo.findAll());
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
    return contentRepo.findAll().stream()
        .map(NavigationValidationService::entryPointRef)
        .collect(Collectors.toSet());
  }

  private Map<String, Object> parseJson(String raw) {
    return jsonUtils.parseMapOrNull(raw);
  }

  private String writeJson(JsonNode value) {
    return jsonUtils.write(value);
  }
}
