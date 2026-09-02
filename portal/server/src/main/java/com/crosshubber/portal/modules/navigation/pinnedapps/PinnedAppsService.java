package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pinned apps (per user) — mirrors the pinned-apps part of {@code
 * portal/src/modules/navigation/navigation.repository.ts}: tree save is a transactional
 * delete+reinsert, client UUIDs are honored.
 */
@Service
public class PinnedAppsService {

  private static final java.util.regex.Pattern UUID_RE =
      java.util.regex.Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
          java.util.regex.Pattern.CASE_INSENSITIVE);

  private final NavigationPinnedAppRepository repo;
  private final EntryPointRepository entryPointRepo;
  private final ObjectMapper objectMapper;

  public PinnedAppsService(
      NavigationPinnedAppRepository repo,
      EntryPointRepository entryPointRepo,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.entryPointRepo = entryPointRepo;
    this.objectMapper = objectMapper;
  }

  /** Known app refs "moduleKey:entryKey". */
  @Transactional(readOnly = true)
  public Set<String> knownAppRefs() {
    Set<String> refs = new LinkedHashSet<>();
    for (EntryPointEntity ep : entryPointRepo.findAll()) {
      refs.add(NavigationValidationService.entryPointRef(ep));
    }
    return refs;
  }

  /** Pinned tree for the user, ordered by sort_order. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> getPinnedTree(String userId) {
    List<NavigationPinnedAppEntity> rows = repo.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId);
    Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
    List<Map<String, Object>> roots = new ArrayList<>();
    for (NavigationPinnedAppEntity row : rows) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", row.getId().toString());
      node.put("nodeType", row.getNodeType());
      if ("folder".equals(row.getNodeType())) {
        node.put("name", row.getName() != null ? row.getName() : "");
      } else {
        node.put("ref", row.getRef() != null ? row.getRef() : "");
      }
      node.put("children", new ArrayList<Map<String, Object>>());
      byId.put(row.getId(), node);
    }
    for (NavigationPinnedAppEntity row : rows) {
      Map<String, Object> node = byId.get(row.getId());
      Map<String, Object> parent = row.getParentId() != null ? byId.get(row.getParentId()) : null;
      if (parent != null) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
        children.add(node);
      } else {
        roots.add(node);
      }
    }
    return roots;
  }

  /** Transactional delete + reinsert of the user's pinned tree. */
  @Transactional
  public void savePinnedTree(String userId, JsonNode nodes) {
    repo.deleteByUserId(userId);
    repo.flush();
    insertNodes(userId, nodes, null);
  }

  private void insertNodes(String userId, JsonNode list, UUID parentId) {
    int order = 0;
    for (JsonNode node : list) {
      boolean isFolder = "folder".equals(node.path("nodeType").asText());
      String clientId = node.path("id").asText(null);
      NavigationPinnedAppEntity entity = new NavigationPinnedAppEntity();
      entity.setId(
          clientId != null && UUID_RE.matcher(clientId).matches()
              ? UUID.fromString(clientId)
              : UUID.randomUUID());
      entity.setUserId(userId);
      entity.setParentId(parentId);
      entity.setNodeType(isFolder ? "folder" : "item");
      entity.setName(isFolder ? node.path("name").asText(null) : null);
      entity.setRef(isFolder ? null : node.path("ref").asText(null));
      entity.setSortOrder(order * 10);
      repo.saveAndFlush(entity);
      JsonNode children = node.get("children");
      if (isFolder && children != null && children.isArray() && !children.isEmpty()) {
        insertNodes(userId, children, entity.getId());
      }
      order++;
    }
  }

  /** Star-toggle: idempotent root item insert for the given ref. */
  @Transactional
  public void pinRef(String userId, String ref) {
    boolean exists = repo.existsByUserIdAndNodeTypeAndRef(userId, "item", ref);
    if (exists) {
      return;
    }
    NavigationPinnedAppEntity entity = new NavigationPinnedAppEntity();
    entity.setId(UUID.randomUUID());
    entity.setUserId(userId);
    entity.setNodeType("item");
    entity.setRef(ref);
    entity.setSortOrder(repo.findMaxSortOrder(userId).orElse(0) + 10);
    repo.save(entity);
  }

  @Transactional
  public void unpinRef(String userId, String ref) {
    repo.deleteByUserIdAndNodeTypeAndRef(userId, "item", ref);
  }
}
