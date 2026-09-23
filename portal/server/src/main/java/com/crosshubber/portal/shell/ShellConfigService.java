package com.crosshubber.portal.shell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.dto.EntryPointDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointGroupDto;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointsService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsRepository;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.dto.ShellConfigDto;
import com.crosshubber.portal.shell.dto.ShellServiceDto;
import com.crosshubber.portal.shell.dto.ShellUserDto;

/**
 * Aggregates the authenticated user's shell config for {@code GET /api/config} triple filtering:
 * visible modules -> allowed groups -> visible entry points.
 */
@Service
public class ShellConfigService {

  private static final Logger log = LoggerFactory.getLogger(ShellConfigService.class);

  private static final List<String> CATEGORY_ORDER =
      List.of("applications", "settings", "features", "user-settings");

  private final ModuleRepository moduleRepo;
  private final EntryPointRepository entryPointRepo;
  private final EntryPointGroupRepository groupRepo;
  private final UserSettingsRepository userSettingsRepo;
  private final JsonUtils jsonUtils;
  private final PortalProperties props;

  @Value("${portal.nats-http-url:}")
  private String natsHttpUrl;

  @Value("${portal.keycloak-public-url:}")
  private String keycloakPublicUrl;

  public ShellConfigService(
      ModuleRepository moduleRepo,
      EntryPointRepository entryPointRepo,
      EntryPointGroupRepository groupRepo,
      UserSettingsRepository userSettingsRepo,
      JsonUtils jsonUtils,
      PortalProperties props) {
    this.moduleRepo = moduleRepo;
    this.entryPointRepo = entryPointRepo;
    this.groupRepo = groupRepo;
    this.userSettingsRepo = userSettingsRepo;
    this.jsonUtils = jsonUtils;
    this.props = props;
  }

  /** Builds the full config payload for the given user. */
  public ShellConfigDto buildConfig(PortalUser user) {
    List<String> userRoles = user.roles();
    log.info("[portal] /api/config for user={} roles={}", user.name(), userRoles);

    Set<String> visibleModuleKeys = visibleModuleKeys(userRoles);
    Map<String, EntryPointGroupEntity> allowedGroups = allowedGroups(userRoles);
    List<EntryPointDto> visibleEps =
        visibleEntryPoints(userRoles, visibleModuleKeys, allowedGroups);
    List<EntryPointGroupDto> usedGroups = usedGroupsDto(visibleEps, allowedGroups);

    return new ShellConfigDto(
        new ShellUserDto(user.sub(), user.name(), user.email(), userRoles),
        loadPreferences(user.sub()),
        visibleEps,
        usedGroups,
        services());
  }

  /** Active modules whose role list matches the user — single {@code findAll}, key set result. */
  private Set<String> visibleModuleKeys(List<String> userRoles) {
    Set<String> keys = new LinkedHashSet<>();
    for (ModuleEntity m : moduleRepo.findAll()) {
      if (Boolean.TRUE.equals(m.getActive())
          && Roles.hasAnyRole(userRoles, Roles.parse(m.getRoles()))) {
        keys.add(m.getKey());
      }
    }
    return keys;
  }

  /**
   * Groups whose role list matches the user, keyed by {@code group_key} in find order. Nav-tree
   * hidden sections (and their descendants) are removed from the runtime view — entry points
   * referencing them fall away with the same rule used for role-blocked groups.
   */
  private Map<String, EntryPointGroupEntity> allowedGroups(List<String> userRoles) {
    Map<String, EntryPointGroupEntity> byKey = new LinkedHashMap<>();
    for (EntryPointGroupEntity g : groupRepo.findAll()) {
      if (Roles.hasAnyRole(userRoles, Roles.parse(g.getRoles()))) {
        byKey.put(g.getGroupKey(), g);
      }
    }
    Set<String> hiddenKeys =
        byKey.values().stream()
            .filter(g -> Boolean.TRUE.equals(g.getHidden()))
            .map(EntryPointGroupEntity::getGroupKey)
            .collect(Collectors.toSet());
    if (!hiddenKeys.isEmpty()) {
      boolean changed = true;
      while (changed) {
        changed = false;
        for (EntryPointGroupEntity g : List.copyOf(byKey.values())) {
          if (g.getParentKey() != null
              && hiddenKeys.contains(g.getParentKey())
              && byKey.containsKey(g.getGroupKey())) {
            byKey.remove(g.getGroupKey());
            hiddenKeys.add(g.getGroupKey());
            changed = true;
          }
        }
      }
    }
    return byKey;
  }

  /**
   * Entry points visible to the user: module visible + active + known category + role match + group
   * allowed (or no group). Category filter excludes admin-settings (admin settings are reachable
   * only via their own routes). Sorted by category order, then sort_order, then name.
   */
  private List<EntryPointDto> visibleEntryPoints(
      List<String> userRoles,
      Set<String> visibleModuleKeys,
      Map<String, EntryPointGroupEntity> allowedGroups) {
    List<EntryPointDto> visible = new ArrayList<>();
    for (EntryPointEntity ep : entryPointRepo.findAll()) {
      if (!visibleModuleKeys.contains(ep.getModuleKey())) {
        continue;
      }
      if (Boolean.FALSE.equals(ep.getActive())) {
        continue;
      }
      // Nav-tree hidden/visible toggle (README Design notes #7): hidden entry
      // points are skipped by the runtime config, not by the tree editor.
      if (Boolean.TRUE.equals(ep.getHidden())) {
        continue;
      }
      if (!CATEGORY_ORDER.contains(ep.getCategory())) {
        continue;
      }
      if (!Roles.hasAnyRole(userRoles, Roles.parse(ep.getRoles()))) {
        continue;
      }
      if (ep.getGroupKey() != null && !allowedGroups.containsKey(ep.getGroupKey())) {
        continue;
      }
      visible.add(EntryPointsService.toOutput(ep));
    }
    visible.sort(BY_CATEGORY_THEN_ORDER_THEN_NAME);
    return visible;
  }

  /** Group DTOs actually referenced by the visible entry points, sorted by sort_order, name. */
  private List<EntryPointGroupDto> usedGroupsDto(
      List<EntryPointDto> visibleEps, Map<String, EntryPointGroupEntity> allowedGroups) {
    Set<String> referencedKeys =
        visibleEps.stream()
            .map(EntryPointDto::groupKey)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return allowedGroups.values().stream()
        .filter(g -> referencedKeys.contains(g.getGroupKey()))
        .map(EntryPointGroupDto::fromEntity)
        .sorted(BY_ORDER_THEN_NAME)
        .toList();
  }

  private static final Comparator<EntryPointDto> BY_CATEGORY_THEN_ORDER_THEN_NAME =
      Comparator.comparingInt((EntryPointDto ep) -> CATEGORY_ORDER.indexOf(ep.category()))
          .thenComparingInt(EntryPointDto::orderOf)
          .thenComparing(ep -> ep.name(), String.CASE_INSENSITIVE_ORDER);

  private static final Comparator<EntryPointGroupDto> BY_ORDER_THEN_NAME =
      Comparator.comparingInt(EntryPointGroupDto::orderOf)
          .thenComparing(g -> g.name(), String.CASE_INSENSITIVE_ORDER);

  /** User settings {@code general} scope as a JSON object (or empty). */
  private Map<String, Object> loadPreferences(String userId) {
    return userSettingsRepo.findByUserId(userId).stream()
        .filter(e -> "general".equals(e.getScope()))
        .findFirst()
        .map(e -> jsonUtils.parseMap(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  /** Service hints for the dashboard, mirrors the services[] in portal.routes.ts. */
  private List<ShellServiceDto> services() {
    List<ShellServiceDto> services = new ArrayList<>();
    if (natsHttpUrl != null && !natsHttpUrl.isBlank()) {
      services.add(new ShellServiceDto("nats", "NATS", natsHttpUrl, "#22d3ee", null));
    }
    if (keycloakPublicUrl != null && !keycloakPublicUrl.isBlank()) {
      services.add(new ShellServiceDto("keycloak", "Keycloak", keycloakPublicUrl, "#34d399", null));
    }
    // Build hint from PortalProperties.db user/database (fallback to "portal")
    String dbUser = "portal";
    String dbDatabase = "portal";
    if (props.getDb() != null) {
      if (props.getDb().getUser() != null && !props.getDb().getUser().isBlank()) {
        dbUser = props.getDb().getUser();
      }
      if (props.getDb().getDatabase() != null && !props.getDb().getDatabase().isBlank()) {
        dbDatabase = props.getDb().getDatabase();
      }
    }
    services.add(
        new ShellServiceDto(
            "postgres",
            "PostgreSQL",
            null,
            "#60a5fa",
            "No web UI \u2014 connect via psql: docker compose exec postgres psql -U "
                + dbUser
                + " -d "
                + dbDatabase));
    return services;
  }
}
