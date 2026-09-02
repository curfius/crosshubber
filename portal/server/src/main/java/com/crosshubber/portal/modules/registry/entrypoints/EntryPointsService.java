package com.crosshubber.portal.modules.registry.entrypoints;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.bootstrap.EmbeddedCatalog;
import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.config.PortalProperties;

/**
 * Entry points domain service — mirrors {@code
 * portal/src/modules/entry-points/entry-points.service.ts}: validation (categories, types,
 * portal-origin guards, registered load paths), upsert and reorder.
 */
@Service
public class EntryPointsService {

  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";
  private static final List<String> CATEGORIES =
      List.of("applications", "settings", "features", "user-settings");
  private static final List<String> TYPES = List.of("iframe", "embedded", "mfe", "link");

  private final EntryPointRepository repo;
  private final PortalProperties props;

  public EntryPointsService(EntryPointRepository repo, PortalProperties props) {
    this.repo = repo;
    this.props = props;
  }

  // ── Output mapping ───────────────────────────────────────────────────

  /** Output DTO — mirrors rowToOutput in entry-points.service.ts. */
  public static Map<String, Object> toOutput(EntryPointEntity ep) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", ep.getId());
    out.put("moduleKey", ep.getModuleKey());
    out.put("entryKey", ep.getEntryKey());
    out.put("category", ep.getCategory());
    out.put("name", ep.getName());
    if (notBlank(ep.getDescription())) {
      out.put("description", ep.getDescription());
    }
    out.put("type", ep.getType());
    if (notBlank(ep.getUrl())) {
      out.put("url", ep.getUrl());
    }
    if (notBlank(ep.getSandbox())) {
      out.put("sandbox", ep.getSandbox());
    }
    if (notBlank(ep.getAllow())) {
      out.put("allow", ep.getAllow());
    }
    if (notBlank(ep.getLoadPath())) {
      out.put("loadPath", ep.getLoadPath());
    }
    if (notBlank(ep.getEntryUrl())) {
      out.put("entryUrl", ep.getEntryUrl());
    }
    if (notBlank(ep.getElement())) {
      out.put("element", ep.getElement());
    }
    if (notBlank(ep.getParentEntryKey())) {
      out.put("parentEntryKey", ep.getParentEntryKey());
    }
    if (notBlank(ep.getGroupKey())) {
      out.put("groupKey", ep.getGroupKey());
    }
    out.put("sortOrder", ep.getSortOrder());
    List<String> roles = Roles.parse(ep.getRoles());
    if (!roles.isEmpty()) {
      out.put("roles", roles);
    }
    out.put("active", ep.getActive());
    if (notBlank(ep.getIcon())) {
      out.put("icon", ep.getIcon());
    }
    if (notBlank(ep.getColor())) {
      out.put("color", ep.getColor());
    }
    out.put("multi", ep.getMulti());
    return out;
  }

  static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  // ── Queries ──────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<EntryPointEntity> list(String moduleKey) {
    if (moduleKey != null) {
      return repo.findByModuleKeyOrderBySortOrderAscNameAsc(moduleKey);
    }
    return repo.findAll(Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name")));
  }

  @Transactional(readOnly = true)
  public List<EntryPointEntity> listByCategory(List<String> categories, boolean activeOnly) {
    List<EntryPointEntity> out = new java.util.ArrayList<>();
    for (String category : categories) {
      out.addAll(repo.findByCategory(category, activeOnly));
    }
    return out;
  }

  // ── Validation (mirrors validateEntryPoint) ──────────────────────────

  /** Returns the validation error message, or null when valid. */
  public String validate(Map<String, Object> input) {
    String moduleKey = string(input.get("moduleKey"));
    String entryKey = string(input.get("entryKey"));
    if (moduleKey == null || !moduleKey.matches(KEY_RE)) {
      return "moduleKey must match [a-z0-9][a-z0-9-]{0,63}";
    }
    if (entryKey == null || !entryKey.matches(KEY_RE)) {
      return "entryKey must match [a-z0-9][a-z0-9-]{0,63}";
    }
    String category = string(input.get("category"));
    if (category == null || !CATEGORIES.contains(category)) {
      return "category must be applications|settings|features|user-settings";
    }
    String name = string(input.get("name"));
    if (name == null || name.isBlank()) {
      return "name is required";
    }
    String type = string(input.get("type"));
    if (type == null || !TYPES.contains(type)) {
      return "type must be iframe|embedded|mfe|link";
    }
    String url = string(input.get("url"));
    if ("iframe".equals(type)) {
      if (url == null || url.isBlank()) {
        return "iframe entry points require a url";
      }
      if (isPortalOrigin(url)) {
        return "iframe url must not point to portal origin — would cause infinite recursion";
      }
    }
    if ("link".equals(type)) {
      if (url == null || url.isBlank()) {
        return "link entry points require a url";
      }
      if (isPortalOrigin(url)) {
        return "link url must not point to portal origin";
      }
    }
    if ("embedded".equals(type)) {
      String loadPath = string(input.get("loadPath"));
      if (loadPath == null || loadPath.isBlank()) {
        return "embedded entry points require a loadPath";
      }
    }
    if ("mfe".equals(type)) {
      String entryUrl = string(input.get("entryUrl"));
      String element = string(input.get("element"));
      if (entryUrl == null || entryUrl.isBlank()) {
        return "mfe entry points require an entryUrl";
      }
      if (element == null || !element.matches("^[a-z][a-z0-9-]*$")) {
        return "mfe entry points require a valid element name";
      }
      if (isPortalOrigin(entryUrl) && !entryUrl.endsWith(".js")) {
        return "mfe entryUrl must not point to portal SPA — use a .js bundle URL";
      }
    }
    return null;
  }

  /** Mirrors validateLoadPath — authoritative source is the embedded catalog. */
  public boolean validateLoadPath(String loadPath) {
    return EmbeddedCatalog.EMBEDDED_LOAD_PATHS.contains(loadPath);
  }

  // ── Commands ─────────────────────────────────────────────────────────

  /** Full-replace upsert on (moduleKey, entryKey) — mirrors repo.upsert. */
  @Transactional
  public EntryPointEntity upsert(Map<String, Object> input) {
    String moduleKey = string(input.get("moduleKey"));
    String entryKey = string(input.get("entryKey"));
    EntryPointEntity ep =
        repo.findByModuleKeyAndEntryKey(moduleKey, entryKey).orElseGet(EntryPointEntity::new);
    boolean isNew = ep.getId() == null;
    if (isNew) {
      ep.setModuleKey(moduleKey);
      ep.setEntryKey(entryKey);
    }
    ep.setCategory(stringOr(input.get("category"), "applications"));
    ep.setName(stringOr(input.get("name"), entryKey));
    ep.setDescription(string(input.get("description")));
    ep.setType(stringOr(input.get("type"), "embedded"));
    ep.setUrl(string(input.get("url")));
    ep.setSandbox(joinSandbox(input.get("sandbox")));
    ep.setAllow(string(input.get("allow")));
    ep.setLoadPath(string(input.get("loadPath")));
    ep.setEntryUrl(string(input.get("entryUrl")));
    ep.setElement(string(input.get("element")));
    ep.setParentEntryKey(string(input.get("parentEntryKey")));
    ep.setGroupKey(string(input.get("groupKey")));
    ep.setSortOrder(intOr(input.get("sortOrder"), 0));
    ep.setRoles(orEmpty(joinRoles(input.get("roles"))));
    ep.setActive(boolOr(input.get("active"), true));
    ep.setIcon(string(input.get("icon")));
    ep.setColor(string(input.get("color")));
    ep.setMulti(boolOr(input.get("multi"), false));
    return repo.save(ep);
  }

  @Transactional
  public boolean remove(long id) {
    if (repo.existsById(id)) {
      repo.deleteById(id);
      return true;
    }
    return false;
  }

  /** Mirrors repo.reorder — sort_order = i*10 in payload order. */
  @Transactional
  public void reorder(List<Long> ids) {
    for (int i = 0; i < ids.size(); i++) {
      final int order = i * 10;
      final long id = ids.get(i);
      repo.findById(id)
          .ifPresent(
              ep -> {
                ep.setSortOrder(order);
                repo.save(ep);
              });
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private boolean isPortalOrigin(String rawUrl) {
    try {
      URI portal = URI.create(props.getPublicBaseUrl());
      URI candidate = URI.create(rawUrl).isAbsolute() ? URI.create(rawUrl) : portal.resolve(rawUrl);
      return sameOrigin(candidate, portal);
    } catch (Exception e) {
      return false;
    }
  }

  static boolean sameOrigin(URI a, URI b) {
    String schemeA = a.getScheme() == null ? "" : a.getScheme();
    String schemeB = b.getScheme() == null ? "" : b.getScheme();
    int portA = a.getPort() == -1 ? defaultPort(a.getScheme()) : a.getPort();
    int portB = b.getPort() == -1 ? defaultPort(b.getScheme()) : b.getPort();
    return schemeA.equalsIgnoreCase(schemeB)
        && a.getHost() != null
        && a.getHost().equalsIgnoreCase(b.getHost())
        && portA == portB;
  }

  private static int defaultPort(String scheme) {
    if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    }
    if ("http".equalsIgnoreCase(scheme)) {
      return 80;
    }
    return -1;
  }

  /** Sandbox tokens: stored as comma-joined text (column flattened in V11). */
  private String joinSandbox(Object sandbox) {
    if (sandbox instanceof List<?> list) {
      return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }
    return sandbox instanceof String s ? s : null;
  }

  private String joinRoles(Object roles) {
    if (roles instanceof List<?> list) {
      return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }
    return roles instanceof String s ? s : null;
  }

  static String string(Object value) {
    return value instanceof String s ? s : null;
  }

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  static String stringOr(Object value, String fallback) {
    return value instanceof String s ? s : fallback;
  }

  static int intOr(Object value, int fallback) {
    return value instanceof Number n ? n.intValue() : fallback;
  }

  static boolean boolOr(Object value, boolean fallback) {
    return value instanceof Boolean b ? b : fallback;
  }

  static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
