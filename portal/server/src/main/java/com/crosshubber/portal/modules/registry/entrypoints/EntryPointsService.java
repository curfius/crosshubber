package com.crosshubber.portal.modules.registry.entrypoints;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.bootstrap.EmbeddedCatalog;
import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.dto.EntryPointDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointUpsertRequest;

/**
 * Entry points domain service: validation (categories, types, portal-origin guards, registered load
 * paths), upsert and reorder.
 */
@Service
public class EntryPointsService {

  private final EntryPointRepository repo;
  private final PortalProperties props;

  public EntryPointsService(EntryPointRepository repo, PortalProperties props) {
    this.repo = repo;
    this.props = props;
  }

  // ── Output mapping ───────────────────────────────────────────────────

  /** Output DTO. */
  public static EntryPointDto toOutput(EntryPointEntity ep) {
    return EntryPointDto.fromEntity(ep);
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

  /**
   * Cross-field validation for the entry point type; scalar constraints (keys, category, name,
   * type) are enforced declaratively on {@link EntryPointUpsertRequest}. Returns the error message,
   * or null when valid.
   */
  public String validate(EntryPointUpsertRequest input) {
    String type = input.type();
    String url = input.url();
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
      String loadPath = input.loadPath();
      if (loadPath == null || loadPath.isBlank()) {
        return "embedded entry points require a loadPath";
      }
    }
    if ("mfe".equals(type)) {
      String entryUrl = input.entryUrl();
      String element = input.element();
      if (entryUrl == null || entryUrl.isBlank()) {
        return "mfe entry points require an entryUrl";
      }
      if (element == null || !element.matches(Keys.ELEMENT_RE)) {
        return "mfe entry points require a valid element name";
      }
      // Check if entryUrl points to portal SPA (must be a .js bundle)
      if (isPortalOrigin(entryUrl)) {
        try {
          URI uri = URI.create(entryUrl);
          String path = uri.getPath();
          if (path == null || !path.endsWith(".js")) {
            return "mfe entryUrl must not point to portal SPA — use a .js bundle URL";
          }
        } catch (Exception e) {
          // If URL parsing fails, fall back to simple endsWith check
          if (!entryUrl.endsWith(".js")) {
            return "mfe entryUrl must not point to portal SPA — use a .js bundle URL";
          }
        }
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
  public EntryPointEntity upsert(EntryPointUpsertRequest input) {
    EntryPointEntity ep =
        repo.findByModuleKeyAndEntryKey(input.moduleKey(), input.entryKey())
            .orElseGet(EntryPointEntity::new);
    boolean isNew = ep.getId() == null;
    if (isNew) {
      ep.setModuleKey(input.moduleKey());
      ep.setEntryKey(input.entryKey());
    }
    ep.setCategory(input.category() != null ? input.category() : "applications");
    ep.setName(input.name() != null ? input.name() : input.entryKey());
    ep.setDescription(input.description());
    ep.setType(input.type() != null ? input.type() : "embedded");
    ep.setUrl(input.url());
    ep.setSandbox(Texts.joinComma(input.sandbox()));
    ep.setAllow(input.allow());
    ep.setLoadPath(input.loadPath());
    ep.setEntryUrl(input.entryUrl());
    ep.setElement(input.element());
    ep.setParentEntryKey(input.parentEntryKey());
    ep.setGroupKey(input.groupKey());
    ep.setSortOrder(input.sortOrder() != null ? input.sortOrder() : 0);
    ep.setRoles(Texts.orEmpty(Texts.joinComma(input.roles())));
    ep.setActive(input.active() != null ? input.active() : true);
    ep.setIcon(input.icon());
    ep.setColor(input.color());
    ep.setMulti(input.multi() != null ? input.multi() : false);
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
    // One batch load instead of a SELECT per id.
    Map<Long, EntryPointEntity> byId = new LinkedHashMap<>();
    repo.findAllById(ids).forEach(ep -> byId.put(ep.getId(), ep));
    for (int i = 0; i < ids.size(); i++) {
      EntryPointEntity ep = byId.get(ids.get(i));
      if (ep != null) {
        ep.setSortOrder(i * 10);
        repo.save(ep);
      }
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
}
