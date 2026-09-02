package com.crosshubber.portal.modules.registry.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Module registry CRUD — mirrors {@code portal/src/modules/modules/modules.service.ts}. */
@Service
public class ModulesService {

  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";

  private final ModuleRepository repo;
  private final ObjectMapper objectMapper;

  public ModulesService(ModuleRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  /** All modules (optionally active only), ordered by name. */
  @Transactional(readOnly = true)
  public List<ModuleEntity> list(boolean includeInactive) {
    List<ModuleEntity> modules = repo.findAll(Sort.by(Sort.Order.asc("name")));
    return includeInactive ? modules : modules.stream().filter(ModuleEntity::getActive).toList();
  }

  /** Output DTO — mirrors rowToModule in modules.service.ts. */
  public Map<String, Object> toOutput(ModuleEntity m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", m.getKey());
    out.put("name", m.getName());
    out.put("active", m.getActive());
    out.put("builtin", m.getBuiltin());
    out.put("managedBy", m.getManagedBy());
    if (notBlank(m.getIcon())) {
      out.put("icon", m.getIcon());
    }
    List<String> roles = com.crosshubber.portal.common.Roles.parse(m.getRoles());
    if (!roles.isEmpty()) {
      out.put("roles", roles);
    }
    if (m.getVersion() != null) {
      out.put("version", m.getVersion());
    }
    if (m.getManifestDigest() != null) {
      out.put("manifestDigest", m.getManifestDigest());
    }
    if (m.getSourceUrl() != null) {
      out.put("sourceUrl", m.getSourceUrl());
    }
    List<String> securityRoles = parseSecurityRoles(m.getSecurityRoles());
    if (!securityRoles.isEmpty()) {
      out.put("securityRoles", securityRoles);
    }
    if (m.getBaseUrl() != null) {
      out.put("baseUrl", m.getBaseUrl());
    }
    if (m.getHealth() != null) {
      out.put("health", m.getHealth());
    }
    return out;
  }

  @Transactional
  public ModuleEntity upsert(Map<String, Object> input) {
    String key = string(input.get("key"));
    String name = string(input.get("name"));
    if (key == null || !key.matches(KEY_RE)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "key must match [a-z0-9][a-z0-9-]{0,63}");
    }
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    ModuleEntity entity =
        repo.findById(key)
            .orElseGet(
                () -> {
                  ModuleEntity created = new ModuleEntity();
                  created.setKey(key);
                  return created;
                });
    entity.setName(name);
    if (input.get("icon") instanceof String icon) {
      entity.setIcon(icon);
    }
    entity.setRoles("");
    entity.setActive(true);
    entity.setBuiltin(false);
    entity.setManagedBy("manual");
    return repo.save(entity);
  }

  @Transactional
  public ModuleEntity setActive(String key, boolean active) {
    ModuleEntity entity =
        repo.findById(key)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "module not found"));
    entity.setActive(active);
    return repo.save(entity);
  }

  /** Deletes a non-builtin module; builtin modules are protected (409). */
  @Transactional
  public void remove(String key) {
    ModuleEntity entity =
        repo.findById(key)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "module not found"));
    if (Boolean.TRUE.equals(entity.getBuiltin())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "built-in modules cannot be deleted");
    }
    repo.delete(entity);
  }

  /** Declared manager role keys from security_roles (used by module-settings). */
  @Transactional(readOnly = true)
  public List<String> securityRoleKeys(String key) {
    return repo.findById(key)
        .map(ModuleEntity::getSecurityRoles)
        .map(this::parseSecurityRoles)
        .orElse(List.of());
  }

  private List<String> parseSecurityRoles(String json) {
    try {
      if (json == null || json.isBlank()) {
        return List.of();
      }
      return objectMapper
          .readValue(json, new TypeReference<List<Map<String, Object>>>() {})
          .stream()
          .filter(r -> r.get("key") instanceof String)
          .map(r -> (String) r.get("key"))
          .toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String string(Object value) {
    return value instanceof String s ? s : null;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
