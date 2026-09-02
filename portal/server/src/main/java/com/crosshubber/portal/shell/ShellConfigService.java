package com.crosshubber.portal.shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsRepository;
import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Aggregates the authenticated user's shell config for {@code GET /api/config} — mirrors {@code
 * portal/src/modules/portal/portal.routes.ts} triple filtering: visible modules -> allowed groups
 * -> visible entry points.
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
  private final ObjectMapper objectMapper;
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
      ObjectMapper objectMapper,
      PortalProperties props) {
    this.moduleRepo = moduleRepo;
    this.entryPointRepo = entryPointRepo;
    this.groupRepo = groupRepo;
    this.userSettingsRepo = userSettingsRepo;
    this.objectMapper = objectMapper;
    this.props = props;
  }

  /** Builds the full config payload for the given user. */
  public Map<String, Object> buildConfig(PortalUser user) {
    List<String> userRoles = user.roles();
    log.info("[portal] /api/config for user={} roles={}", user.name(), userRoles);

    // 1. Modules visible to the user (active + role match)
    Map<String, ModuleEntity> moduleByKey = new LinkedHashMap<>();
    for (ModuleEntity m : moduleRepo.findAll()) {
      moduleByKey.put(m.getKey(), m);
    }
    Map<String, List<String>> moduleRolesByKey = new HashMap<>();
    List<String> visibleModuleKeys = new ArrayList<>();
    for (ModuleEntity m : moduleRepo.findAll()) {
      List<String> roles = Roles.parse(m.getRoles());
      moduleRolesByKey.put(m.getKey(), roles);
      if (Boolean.TRUE.equals(m.getActive()) && Roles.hasAnyRole(userRoles, roles)) {
        visibleModuleKeys.add(m.getKey());
      }
    }

    // 2. Groups allowed by role
    List<EntryPointGroupEntity> allGroups = groupRepo.findAll();
    List<EntryPointGroupEntity> allowedGroups = new ArrayList<>();
    for (EntryPointGroupEntity g : allGroups) {
      if (Roles.hasAnyRole(userRoles, Roles.parse(g.getRoles()))) {
        allowedGroups.add(g);
      }
    }
    Map<String, EntryPointGroupEntity> allowedGroupByKey = new LinkedHashMap<>();
    for (EntryPointGroupEntity g : allowedGroups) {
      allowedGroupByKey.put(g.getGroupKey(), g);
    }

    // 3. Entry points visible (module visible + role + group allowed)
    //    Category filter: exclude admin-settings (Node parity)
    List<Map<String, Object>> visibleEps = new ArrayList<>();
    for (EntryPointEntity ep : entryPointRepo.findAll()) {
      if (!visibleModuleKeys.contains(ep.getModuleKey())) {
        continue;
      }
      if (Boolean.FALSE.equals(ep.getActive())) {
        continue;
      }
      if (!CATEGORY_ORDER.contains(ep.getCategory())) {
        continue;
      }
      if (!Roles.hasAnyRole(userRoles, Roles.parse(ep.getRoles()))) {
        continue;
      }
      if (ep.getGroupKey() != null && !allowedGroupByKey.containsKey(ep.getGroupKey())) {
        continue;
      }
      visibleEps.add(toEntryPointDto(ep));
    }
    // Sort by category order, then sort_order, then name
    visibleEps.sort(
        (a, b) -> {
          int catA = CATEGORY_ORDER.indexOf(a.get("category"));
          int catB = CATEGORY_ORDER.indexOf(b.get("category"));
          if (catA != catB) {
            return Integer.compare(catA, catB);
          }
          int orderA = a.get("sortOrder") instanceof Number n ? n.intValue() : 0;
          int orderB = b.get("sortOrder") instanceof Number n ? n.intValue() : 0;
          if (orderA != orderB) {
            return Integer.compare(orderA, orderB);
          }
          String nameA = (String) a.get("name");
          String nameB = (String) b.get("name");
          return nameA != null ? nameA.compareToIgnoreCase(nameB) : 0;
        });

    // Groups actually used by visible entry points
    List<Map<String, Object>> usedGroups = new ArrayList<>();
    for (Map<String, Object> ep : visibleEps) {
      String groupKey = (String) ep.get("groupKey");
      if (groupKey != null
          && allowedGroupByKey.containsKey(groupKey)
          && usedGroups.stream().noneMatch(g -> groupKey.equals(g.get("groupKey")))) {
        EntryPointGroupEntity g = allowedGroupByKey.get(groupKey);
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
        usedGroups.add(dto);
      }
    }
    // Sort groups by sort_order, then name
    usedGroups.sort(
        (a, b) -> {
          int orderA = a.get("sortOrder") instanceof Number n ? n.intValue() : 0;
          int orderB = b.get("sortOrder") instanceof Number n ? n.intValue() : 0;
          if (orderA != orderB) {
            return Integer.compare(orderA, orderB);
          }
          String nameA = (String) a.get("name");
          String nameB = (String) b.get("name");
          return nameA != null ? nameA.compareToIgnoreCase(nameB) : 0;
        });

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

  /** User settings {@code general} scope as a JSON object (or empty). */
  private Map<String, Object> loadPreferences(String userId) {
    return userSettingsRepo.findByUserId(userId).stream()
        .filter(e -> "general".equals(e.getScope()))
        .findFirst()
        .map(e -> parseJson(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      JsonNode node = objectMapper.readTree(raw);
      return objectMapper.convertValue(
          node,
          objectMapper
              .getTypeFactory()
              .constructMapType(LinkedHashMap.class, String.class, Object.class));
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
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

  /** Entry point DTO — omits null fields, mirrors epSvc.rowToOutput. */
  public static Map<String, Object> toEntryPointDto(EntryPointEntity ep) {
    return com.crosshubber.portal.modules.registry.entrypoints.EntryPointsService.toOutput(ep);
  }
}
