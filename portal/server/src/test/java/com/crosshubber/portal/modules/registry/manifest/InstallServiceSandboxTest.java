package com.crosshubber.portal.modules.registry.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.crosshubber.portal.auth.kcadmin.KcAdminClient;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Manifest installs must store sandbox as comma-joined tokens, matching ModuleContentsService. */
class InstallServiceSandboxTest {

  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();
  private final ModuleRepository moduleRepo = Mockito.mock(ModuleRepository.class);
  private final ModuleContentRepository contentRepo = Mockito.mock(ModuleContentRepository.class);
  private final InstallService svc =
      new InstallService(
          moduleRepo,
          contentRepo,
          Mockito.mock(ModuleVersionRepository.class),
          new ManifestValidator(new PortalProperties(), mapper),
          Mockito.mock(KcAdminClient.class),
          mapper);

  @BeforeEach
  void stubRepos() {
    Mockito.when(moduleRepo.findById("demo")).thenReturn(Optional.empty());
    Mockito.when(contentRepo.findByModuleKey("demo")).thenReturn(List.of());
  }

  @Test
  void applyInstallStoresSandboxAsCommaJoinedTokens() throws Exception {
    JsonNode manifest =
        mapper.readTree(manifestWithSandbox("[\"allow-scripts\", \"allow-popups\"]"));

    InstallService.InstallResult result = svc.applyInstall(manifest, "tester");

    assertTrue(result.ok());
    assertEquals("demo", result.moduleKey());
    assertEquals("allow-scripts,allow-popups", savedContent().getSandbox());
  }

  @Test
  void applyInstallStoresEmptySandboxAsNull() throws Exception {
    JsonNode manifest = mapper.readTree(manifestWithSandbox("[]"));

    svc.applyInstall(manifest, "tester");

    assertNull(savedContent().getSandbox());
  }

  private ModuleContentEntity savedContent() {
    ArgumentCaptor<ModuleContentEntity> captor = ArgumentCaptor.forClass(ModuleContentEntity.class);
    Mockito.verify(contentRepo).save(captor.capture());
    return captor.getValue();
  }

  private static String manifestWithSandbox(String sandboxJson) {
    return """
        {
          "key": "demo",
          "name": "Demo",
          "baseUrl": "https://demo.example.com",
          "content": {
            "applications": [
              {"key": "home", "name": "Home", "type": "iframe",
               "url": "https://demo.example.com/app", "sandbox": %s}
            ]
          }
        }
        """
        .formatted(sandboxJson);
  }
}
