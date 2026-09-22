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
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointsService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsRepository;
import com.crosshubber.portal.security.PortalUser;

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
  public Map<String, Object> buildConfig(PortalUser user) {
    List<String> userRoles = user.roles();
    log.info("[portal] /api/config for user={} roles={}", user.name(), userRoles);

    Set<String> visibleModuleKeys = visibleModuleKeys(userRoles);
    Map<String, EntryPointGroupEntity> allowedGroups = allowedGroups(userRoles);
    List<Map<String, Object>> visibleEps =
        visibleEntryPoints(userRoles, visibleModuleKeys, allowedGroups);
    List<Map<String, Object>> usedGroups = usedGroupsDto(visibleEps, allowedGroups);

    Map<String, Object> body = new LinkedHashMap<>();
    Map<String, Object> userDto = new LinkedHashMap<>();
    userDto.put("sub", user.sub());
    userDto.put("name", user.name());
    if (user.email() != null) {
      userDto.put("email", user.email());
    }
    userDto.put("roles", userRoles);
    body.put("user", userDto);
    body.put("preferences", loadPreferences(user.sub()));
    body.put("entryPoints", visibleEps);
    body.put("entryPointGroups", usedGroups);
    body.put("services", services());
    return body;
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
  private List<Map<String, Object>> visibleEntryPoints(
      List<String> userRoles,
      Set<String> visibleModuleKeys,
      Map<String, EntryPointGroupEntity> allowedGroups) {
    List<Map<String, Object>> visible = new ArrayList<>();
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
  private List<Map<String, Object>> usedGroupsDto(
      List<Map<String, Object>> visibleEps, Map<String, EntryPointGroupEntity> allowedGroups) {
    Set<String> referencedKeys =
        visibleEps.stream()
            .map(ep -> (String) ep.get("groupKey"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return allowedGroups.values().stream()
        .filter(g -> referencedKeys.contains(g.getGroupKey()))
        .map(ShellConfigService::toGroupDto)
        .sorted(BY_ORDER_THEN_NAME)
        .toList();
  }

  private static Map<String, Object> toGroupDto(EntryPointGroupEntity g) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("groupKey", g.getGroupKey());
    dto.put("category", g.getCategory());
    dto.put("name", g.getName());
    dto.put("parentKey", g.getParentKey());
    dto.put("sortOrder", g.getSortOrder());
    if (g.getIcon() != null) {
      dto.put("icon", g.getIcon());
    }
    List<String> roles = Roles.parse(g.getRoles());
    if (!roles.isEmpty()) {
      dto.put("roles", roles);
    }
    return dto;
  }

  private static int orderOf(Map<String, Object> dto) {
    return dto.get("sortOrder") instanceof Number n ? n.intValue() : 0;
  }

  private static int compareByName(Map<String, Object> a, Map<String, Object> b) {
    String nameA = (String) a.get("name");
    String nameB = (String) b.get("name");
    return nameA != null ? nameA.compareToIgnoreCase(nameB) : 0;
  }

  private static final Comparator<Map<String, Object>> BY_CATEGORY_THEN_ORDER_THEN_NAME =
      (a, b) -> {
        int catA = CATEGORY_ORDER.indexOf(a.get("category"));
        int catB = CATEGORY_ORDER.indexOf(b.get("category"));
        if (catA != catB) {
          return Integer.compare(catA, catB);
        }
        int byOrder = Integer.compare(orderOf(a), orderOf(b));
        return byOrder != 0 ? byOrder : compareByName(a, b);
      };

  private static final Comparator<Map<String, Object>> BY_ORDER_THEN_NAME =
      (a, b) -> {
        int byOrder = Integer.compare(orderOf(a), orderOf(b));
        return byOrder != 0 ? byOrder : compareByName(a, b);
      };

  /** User settings {@code general} scope as a JSON object (or empty). */
  private Map<String, Object> loadPreferences(String userId) {
    return userSettingsRepo.findByUserId(userId).stream()
        .filter(e -> "general".equals(e.getScope()))
        .findFirst()
        .map(e -> jsonUtils.parseMap(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  /** Service hints for the dashboard, mirrors the services[] in portal.routes.ts. */
  private List<Map<String, Object>> services() {
    List<Map<String, Object>> services = new ArrayList<>();
    if (natsHttpUrl != null && !natsHttpUrl.isBlank()) {
      Map<String, Object> nats = new LinkedHashMap<>();
      nats.put("key", "nats");
      nats.put("name", "NATS");
      nats.put("url", natsHttpUrl);
      nats.put("color", "#22d3ee");
      services.add(nats);
    }
    if (keycloakPublicUrl != null && !keycloakPublicUrl.isBlank()) {
      Map<String, Object> kc = new LinkedHashMap<>();
      kc.put("key", "keycloak");
      kc.put("name", "Keycloak");
      kc.put("url", keycloakPublicUrl);
      kc.put("color", "#34d399");
      services.add(kc);
    }
    Map<String, Object> pg = new LinkedHashMap<>();
    pg.put("key", "postgres");
    pg.put("name", "PostgreSQL");
    pg.put("url", null);
    pg.put("color", "#60a5fa");
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
    pg.put(
        "hint",
        "No web UI \u2014 connect via psql: docker compose exec postgres psql -U "
            + dbUser
            + " -d "
            + dbDatabase);
    services.add(pg);
    return services;
  }
}
