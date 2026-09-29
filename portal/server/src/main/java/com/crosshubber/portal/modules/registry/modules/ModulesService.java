package com.crosshubber.portal.modules.registry.modules;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.modules.registry.dto.ModuleDto;
import com.crosshubber.portal.modules.registry.dto.ModuleUpsertRequest;
import com.crosshubber.portal.modules.registry.dto.SecurityRoleDto;

/** Module registry CRUD. */
@Service
public class ModulesService {

  private final ModuleRepository repo;
  private final JsonUtils jsonUtils;

  public ModulesService(ModuleRepository repo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.jsonUtils = jsonUtils;
  }

  /** All modules (optionally active only), ordered by name. */
  @Transactional(readOnly = true)
  public List<ModuleEntity> list(boolean includeInactive) {
    List<ModuleEntity> modules = repo.findAll(Sort.by(Sort.Order.asc("name")));
    return includeInactive ? modules : modules.stream().filter(m -> m.getActive()).toList();
  }

  /** Output DTO. */
  public ModuleDto toOutput(ModuleEntity m) {
    return ModuleDto.fromEntity(m, parseSecurityRoleDtos(m.getSecurityRoles()));
  }

  @Transactional
  public ModuleEntity upsert(ModuleUpsertRequest input) {
    String key = input.key();
    String name = input.name();
    boolean isNew = !repo.existsById(key);
    ModuleEntity entity =
        repo.findById(key)
            .orElseGet(
                () -> {
                  ModuleEntity created = new ModuleEntity();
                  created.setKey(key);
                  return created;
                });
    // COALESCE semantics: set from input if present, else preserve existing
    entity.setName(name);
    if (input.icon() != null) {
      entity.setIcon(input.icon());
    }
    if (input.roles() != null) {
      entity.setRoles(input.roles());
    } else if (isNew) {
      entity.setRoles("");
    }
    if (input.active() != null) {
      entity.setActive(input.active());
    } else if (isNew) {
      entity.setActive(true);
    }
    // builtin: sticky — never un-set
    if (input.builtin() != null) {
      entity.setBuiltin(Boolean.TRUE.equals(entity.getBuiltin()) || input.builtin());
    } else if (isNew) {
      entity.setBuiltin(false);
    }
    if (input.version() != null) {
      entity.setVersion(input.version());
    }
    if (input.manifestDigest() != null) {
      entity.setManifestDigest(input.manifestDigest());
    }
    if (input.managedBy() != null) {
      entity.setManagedBy(input.managedBy());
    } else if (isNew) {
      entity.setManagedBy("manual");
    }
    if (input.sourceUrl() != null) {
      entity.setSourceUrl(input.sourceUrl());
    }
    if (input.baseUrl() != null) {
      entity.setBaseUrl(input.baseUrl());
    }
    if (input.health() != null) {
      entity.setHealth(input.health());
    }
    // security_roles is always overwritten (missing input → '[]') because the ON CONFLICT
    // COALESCE is fed a never-null EXCLUDED value.
    entity.setSecurityRoles(
        writeJson(input.securityRoles() == null ? java.util.List.of() : input.securityRoles()));
    return repo.save(entity);
  }

  @Transactional
  public ModuleEntity setActive(String key, boolean active) {
    ModuleEntity entity =
        repo.findById(key)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "module not found"));
    if (Boolean.TRUE.equals(entity.getBuiltin())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "builtin module availability is tenant-config-owned");
    }
    entity.setActive(active);
    return repo.save(entity);
  }

  /**
   * Deletes a non-builtin module; builtin modules are protected (409). Returns false if missing.
   */
  @Transactional
  public boolean remove(String key) {
    ModuleEntity entity = repo.findById(key).orElse(null);
    if (entity == null) {
      return false;
    }
    if (Boolean.TRUE.equals(entity.getBuiltin())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "built-in modules cannot be deleted");
    }
    repo.delete(entity);
    return true;
  }

  /** Declared manager role keys from security_roles (used by module-settings). */
  @Transactional(readOnly = true)
  public List<String> securityRoleKeys(String key) {
    return repo.findById(key)
        .map(m -> m.getSecurityRoles())
        .map(this::parseSecurityRoleKeys)
        .orElse(List.of());
  }

  private List<SecurityRoleDto> parseSecurityRoleDtos(String json) {
    return jsonUtils.parseList(json, SecurityRoleDto.class);
  }

  private List<String> parseSecurityRoleKeys(String json) {
    return parseSecurityRoleDtos(json).stream().map(r -> r.key()).toList();
  }

  private String writeJson(Object value) {
    return jsonUtils.write(value);
  }
}
