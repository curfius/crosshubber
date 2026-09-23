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
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Manifest installs must store sandbox as comma-joined tokens, matching EntryPointsService. */
class InstallServiceSandboxTest {

  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();
  private final ModuleRepository moduleRepo = Mockito.mock(ModuleRepository.class);
  private final EntryPointRepository entryPointRepo = Mockito.mock(EntryPointRepository.class);
  private final InstallService svc =
      new InstallService(
          moduleRepo,
          entryPointRepo,
          Mockito.mock(ModuleVersionRepository.class),
          new ManifestValidator(new PortalProperties(), mapper),
          Mockito.mock(KcAdminClient.class),
          mapper);

  @BeforeEach
  void stubRepos() {
    Mockito.when(moduleRepo.findById("demo")).thenReturn(Optional.empty());
    Mockito.when(entryPointRepo.findByModuleKey("demo")).thenReturn(List.of());
  }

  @Test
  void applyInstallStoresSandboxAsCommaJoinedTokens() throws Exception {
    JsonNode manifest =
        mapper.readTree(manifestWithSandbox("[\"allow-scripts\", \"allow-popups\"]"));

    InstallService.InstallResult result = svc.applyInstall(manifest, "tester");

    assertTrue(result.ok());
    assertEquals("demo", result.moduleKey());
    assertEquals("allow-scripts,allow-popups", savedEntryPoint().getSandbox());
  }

  @Test
  void applyInstallStoresEmptySandboxAsNull() throws Exception {
    JsonNode manifest = mapper.readTree(manifestWithSandbox("[]"));

    svc.applyInstall(manifest, "tester");

    assertNull(savedEntryPoint().getSandbox());
  }

  private EntryPointEntity savedEntryPoint() {
    ArgumentCaptor<EntryPointEntity> captor = ArgumentCaptor.forClass(EntryPointEntity.class);
    Mockito.verify(entryPointRepo).save(captor.capture());
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
