package com.crosshubber.portal.modules.registry.modulecontents;

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

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.dto.ModuleContentUpsertRequest;

/** Sort-order append semantics of the content upsert (registry Phase 4). */
class ModuleContentsServiceTest {

  private ModuleContentRepository repo;
  private ModuleContentsService service;

  @BeforeEach
  void setUp() {
    repo = mock(ModuleContentRepository.class);
    service = new ModuleContentsService(repo, mock(PortalProperties.class));
  }

  private static ModuleContentUpsertRequest request(Integer sortOrder) {
    return new ModuleContentUpsertRequest(
        "demo",
        "page",
        "applications",
        "Page",
        "embedded",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        sortOrder,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void appendsNewContentAfterModulePeers() {
    when(repo.findByModuleKeyAndContentKey("demo", "page")).thenReturn(Optional.empty());
    when(repo.findMaxSortOrder("demo")).thenReturn(Optional.of(30));

    service.upsert(request(null));

    ArgumentCaptor<ModuleContentEntity> captor = ArgumentCaptor.forClass(ModuleContentEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(40, captor.getValue().getSortOrder());
  }

  @Test
  void firstContentStartsAtZero() {
    when(repo.findByModuleKeyAndContentKey("demo", "page")).thenReturn(Optional.empty());
    when(repo.findMaxSortOrder("demo")).thenReturn(Optional.empty());

    service.upsert(request(null));

    ArgumentCaptor<ModuleContentEntity> captor = ArgumentCaptor.forClass(ModuleContentEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(0, captor.getValue().getSortOrder());
  }

  @Test
  void keepsStoredOrderWhenSortOrderOmitted() {
    ModuleContentEntity existing = new ModuleContentEntity();
    existing.setId(7L);
    existing.setSortOrder(40);
    when(repo.findByModuleKeyAndContentKey("demo", "page")).thenReturn(Optional.of(existing));

    service.upsert(request(null));

    verify(repo).save(existing);
    assertEquals(40, existing.getSortOrder());
    verify(repo, never()).findMaxSortOrder(any());
  }

  @Test
  void explicitSortOrderWins() {
    when(repo.findByModuleKeyAndContentKey("demo", "page")).thenReturn(Optional.empty());

    service.upsert(request(5));

    ArgumentCaptor<ModuleContentEntity> captor = ArgumentCaptor.forClass(ModuleContentEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(5, captor.getValue().getSortOrder());
    verify(repo, never()).findMaxSortOrder(any());
  }
}
