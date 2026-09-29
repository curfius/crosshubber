package com.crosshubber.portal.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.bootstrap.TenantMetaEntity;
import com.crosshubber.portal.bootstrap.TenantMetaRepository;

/** Branding surface: tenant_meta read-through with repo-baseline fallbacks. */
class BrandingControllerTest {

  private TenantMetaRepository repo;
  private BrandingController controller;

  @BeforeEach
  void setUp() {
    repo = mock(TenantMetaRepository.class);
    controller = new BrandingController(repo);
  }

  private static TenantMetaEntity meta(String key, String json) {
    TenantMetaEntity e = new TenantMetaEntity();
    e.setKey(key);
    e.setValue(json);
    return e;
  }

  @Test
  void returnsRepoBaselineWhenNoBrandingRows() {
    var body = controller.branding();
    assertEquals("Crosshubber", body.get("name"));
    assertEquals("Crosshubber Portal", body.get("title"));
    assertEquals(false, body.containsKey("logoUrl"));
  }

  @Test
  void readsBrandingRowsWrittenByTheReconciler() {
    when(repo.findById("branding.name")).thenReturn(Optional.of(meta("branding.name", "\"Acme\"")));
    when(repo.findById("branding.title"))
        .thenReturn(Optional.of(meta("branding.title", "\"Acme Portal\"")));
    when(repo.findById("branding.logoUrl"))
        .thenReturn(Optional.of(meta("branding.logoUrl", "\"https://cdn/acme.png\"")));

    var body = controller.branding();
    assertEquals("Acme", body.get("name"));
    assertEquals("Acme Portal", body.get("title"));
    assertEquals("https://cdn/acme.png", body.get("logoUrl"));
  }

  @Test
  void nullOrBlankRowsFallBack() {
    when(repo.findById("branding.name")).thenReturn(Optional.of(meta("branding.name", "null")));
    when(repo.findById("branding.title")).thenReturn(Optional.of(meta("branding.title", "\"  \"")));

    var body = controller.branding();
    assertEquals("Crosshubber", body.get("name"));
    assertEquals("Crosshubber Portal", body.get("title"));
  }

  @Test
  void missingTitleFallsBackToNameDerivedTitle() {
    when(repo.findById("branding.name")).thenReturn(Optional.of(meta("branding.name", "\"Acme\"")));

    var body = controller.branding();
    assertEquals("Acme", body.get("name"));
    assertEquals("Acme Portal", body.get("title"));
  }
}
