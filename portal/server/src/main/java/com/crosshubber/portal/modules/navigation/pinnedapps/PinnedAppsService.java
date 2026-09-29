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

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;

import tools.jackson.databind.JsonNode;

/**
 * Pinned apps (per user) — tree save is a transactional delete+reinsert; client UUIDs are honored.
 */
@Service
public class PinnedAppsService {

  private final NavigationPinnedAppRepository repo;
  private final ModuleContentRepository contentRepo;

  public PinnedAppsService(
      NavigationPinnedAppRepository repo, ModuleContentRepository contentRepo) {
    this.repo = repo;
    this.contentRepo = contentRepo;
  }

  /** Known app refs "moduleKey:contentKey". */
  @Transactional(readOnly = true)
  public Set<String> knownAppRefs() {
    Set<String> refs = new LinkedHashSet<>();
    for (ModuleContentEntity ep : contentRepo.findAll()) {
      refs.add(NavigationValidationService.entryPointRef(ep));
    }
    return refs;
  }

  /** Pinned tree for the user, ordered by sort_order. */
  @Transactional(readOnly = true)
  public List<PinnedNodeDto> getPinnedTree(String userId) {
    List<NavigationPinnedAppEntity> rows = repo.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId);
    Map<UUID, PinnedNodeDto> byId = new LinkedHashMap<>();
    List<PinnedNodeDto> roots = new ArrayList<>();
    for (NavigationPinnedAppEntity row : rows) {
      boolean folder = row.getNodeType() == PinnedNodeType.FOLDER;
      PinnedNodeDto node =
          new PinnedNodeDto(
              row.getId().toString(),
              row.getNodeType() == null ? null : row.getNodeType().value(),
              folder ? (row.getName() != null ? row.getName() : "") : "",
              !folder ? (row.getRef() != null ? row.getRef() : "") : "",
              new ArrayList<>());
      byId.put(row.getId(), node);
    }
    for (NavigationPinnedAppEntity row : rows) {
      PinnedNodeDto node = byId.get(row.getId());
      PinnedNodeDto parent = row.getParentId() != null ? byId.get(row.getParentId()) : null;
      if (parent != null) {
        parent.children().add(node);
      } else {
        roots.add(node);
      }
    }
    return roots;
  }

  /** Transactional delete + reinsert of the user's pinned tree. */
  @Transactional
  public void savePinnedTree(String userId, JsonNode nodes) {
    // Single bulk statement (see deleteAllForUser) — per-entity deletes break on
    // the self-referential ON DELETE CASCADE.
    repo.deleteAllForUser(userId);
    // IDs are client-generated (UUIDs set before persist), so the whole tree can be
    // collected first and batch-saved — no per-row flush needed for parent resolution.
    List<NavigationPinnedAppEntity> rows = new ArrayList<>();
    insertNodes(userId, nodes, null, rows);
    repo.saveAll(rows);
  }

  private void insertNodes(
      String userId, JsonNode list, UUID parentId, List<NavigationPinnedAppEntity> out) {
    int order = 0;
    for (JsonNode node : list) {
      boolean isFolder = "folder".equals(node.path("nodeType").asString());
      String clientId = node.path("id").asString(null);
      NavigationPinnedAppEntity entity = new NavigationPinnedAppEntity();
      entity.setId(
          clientId != null && Keys.UUID_PATTERN.matcher(clientId).matches()
              ? UUID.fromString(clientId)
              : UUID.randomUUID());
      entity.setUserId(userId);
      entity.setParentId(parentId);
      entity.setNodeType(isFolder ? PinnedNodeType.FOLDER : PinnedNodeType.ITEM);
      entity.setName(isFolder ? node.path("name").asString(null) : null);
      entity.setRef(isFolder ? null : node.path("ref").asString(null));
      entity.setSortOrder(order * 10);
      out.add(entity);
      JsonNode children = node.get("children");
      if (isFolder && children != null && children.isArray() && !children.isEmpty()) {
        insertNodes(userId, children, entity.getId(), out);
      }
      order++;
    }
  }

  /** Star-toggle: idempotent root item insert for the given ref. */
  @Transactional
  public void pinRef(String userId, String ref) {
    boolean exists = repo.existsByUserIdAndNodeTypeAndRef(userId, PinnedNodeType.ITEM, ref);
    if (exists) {
      return;
    }
    NavigationPinnedAppEntity entity = new NavigationPinnedAppEntity();
    entity.setId(UUID.randomUUID());
    entity.setUserId(userId);
    entity.setNodeType(PinnedNodeType.ITEM);
    entity.setRef(ref);
    entity.setSortOrder(repo.findMaxSortOrder(userId).orElse(0) + 10);
    repo.save(entity);
  }

  @Transactional
  public void unpinRef(String userId, String ref) {
    repo.deleteByUserIdAndNodeTypeAndRef(userId, PinnedNodeType.ITEM, ref);
  }
}
