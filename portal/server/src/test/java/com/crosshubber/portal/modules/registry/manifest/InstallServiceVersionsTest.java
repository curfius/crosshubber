package com.crosshubber.portal.modules.registry.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.crosshubber.portal.auth.kcadmin.KcAdminClient;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.dto.ModuleVersionDto;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

class InstallServiceVersionsTest {

  private final ModuleVersionRepository versionRepo = Mockito.mock(ModuleVersionRepository.class);
  private final InstallService svc =
      new InstallService(
          Mockito.mock(ModuleRepository.class),
          Mockito.mock(EntryPointRepository.class),
          versionRepo,
          new ManifestValidator(new PortalProperties(), new JacksonConfig().jsonMapper()),
          Mockito.mock(KcAdminClient.class),
          new JacksonConfig().jsonMapper());

  @Test
  void listVersionsMapsEntityRowsToTypedDtos() {
    ModuleVersionEntity row = new ModuleVersionEntity();
    row.setModuleKey("m");
    row.setVersion("2.0");
    row.setDigest("abc123");
    row.setManifest("{}");
    row.setInstalledAt(Instant.parse("2026-09-21T10:15:30.123Z"));
    row.setInstalledBy("tenant/alice (sub-1)");
    row.setStatus("active");
    Mockito.when(versionRepo.findByModuleKeyOrderByInstalledAtDesc("m")).thenReturn(List.of(row));

    List<ModuleVersionDto> versions = svc.listVersions("m");

    assertEquals(1, versions.size());
    ModuleVersionDto dto = versions.get(0);
    assertEquals("2.0", dto.version());
    assertEquals("abc123", dto.digest());
    // installedAt stays an ISO-8601 string with millisecond precision (Node parity).
    assertEquals("2026-09-21T10:15:30.123Z", dto.installedAt());
    assertEquals("tenant/alice (sub-1)", dto.installedBy());
    assertEquals("active", dto.status());
  }
}
