package com.crosshubber.portal.modules.navigation.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;

/** Sort-order append semantics of the group upsert (registry Phase 4). */
class NavigationGroupsServiceTest {

  private NavigationGroupRepository repo;
  private NavigationGroupsService service;

  @BeforeEach
  void setUp() {
    repo = mock(NavigationGroupRepository.class);
    service = new NavigationGroupsService(repo);
  }

  private static NavigationGroupUpsertRequest request(String groupKey, Integer sortOrder) {
    return new NavigationGroupUpsertRequest(
        groupKey, "admin-settings", "Section", null, sortOrder, null, null);
  }

  @Test
  void appendsNewGroupAfterCategoryPeers() {
    when(repo.findByGroupKey("nav-new")).thenReturn(Optional.empty());
    when(repo.findMaxSortOrder(ModuleContentCategory.ADMIN_SETTINGS)).thenReturn(Optional.of(20));

    service.upsert(request("nav-new", null));

    ArgumentCaptor<NavigationGroupEntity> captor =
        ArgumentCaptor.forClass(NavigationGroupEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(30, captor.getValue().getSortOrder());
  }

  @Test
  void firstGroupStartsAtZero() {
    when(repo.findByGroupKey("nav-new")).thenReturn(Optional.empty());
    when(repo.findMaxSortOrder(ModuleContentCategory.ADMIN_SETTINGS)).thenReturn(Optional.empty());

    service.upsert(request("nav-new", null));

    ArgumentCaptor<NavigationGroupEntity> captor =
        ArgumentCaptor.forClass(NavigationGroupEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(0, captor.getValue().getSortOrder());
  }

  @Test
  void keepsStoredOrderWhenSortOrderOmitted() {
    NavigationGroupEntity existing = new NavigationGroupEntity();
    existing.setId(9L);
    existing.setSortOrder(40);
    when(repo.findByGroupKey("nav-old")).thenReturn(Optional.of(existing));

    service.upsert(request("nav-old", null));

    verify(repo).save(existing);
    assertEquals(40, existing.getSortOrder());
    verify(repo, never()).findMaxSortOrder(any());
  }

  @Test
  void explicitSortOrderWins() {
    when(repo.findByGroupKey("nav-new")).thenReturn(Optional.empty());

    service.upsert(request("nav-new", 5));

    ArgumentCaptor<NavigationGroupEntity> captor =
        ArgumentCaptor.forClass(NavigationGroupEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(5, captor.getValue().getSortOrder());
    verify(repo, never()).findMaxSortOrder(any());
  }
}
