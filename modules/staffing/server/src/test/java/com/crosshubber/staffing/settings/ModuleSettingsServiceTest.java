package com.crosshubber.staffing.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Settings storage: defaults, tool toggles, match params, validation. */
class ModuleSettingsServiceTest {

  private ModuleSettingRepository repo;
  private ModuleSettingsService service;

  @BeforeEach
  void setUp() {
    repo = mock(ModuleSettingRepository.class);
    service = new ModuleSettingsService(repo, new JsonMapper());
    // Stateful in-memory repo so update-then-read round-trips like the real DB.
    java.util.Map<String, ModuleSettingEntity> store =
        new java.util.concurrent.ConcurrentHashMap<>();
    when(repo.save(any(ModuleSettingEntity.class)))
        .thenAnswer(
            inv -> {
              ModuleSettingEntity e = inv.getArgument(0);
              store.put(e.getKey(), e);
              return e;
            });
    when(repo.findByKey(any()))
        .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
  }

  @Test
  void defaultsServeAllToolsEnabledAndTopN5() {
    assertThat(service.isToolEnabled("list_rfps")).isTrue();
    assertThat(service.matchTopN()).isEqualTo(5);
    assertThat(service.view().tools().get("create_match_run")).isTrue();
    assertThat(service.view().match().topN()).isEqualTo(5);
  }

  @Test
  void toolToggleDisablesOnlyThatTool() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            java.util.Map.of("match_candidates", false), null));

    assertThat(service.isToolEnabled("match_candidates")).isFalse();
    assertThat(service.isToolEnabled("list_rfps")).isTrue();
  }

  @Test
  void matchTopNUpdateWinsForDispatch() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null, new ModuleSettingsService.MatchUpdate(12)));

    assertThat(service.matchTopN()).isEqualTo(12);
  }

  @Test
  void validateRejectsUnknownToolAndBadTopN() {
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    java.util.Map.of("nope", true), null)))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    null, new ModuleSettingsService.MatchUpdate(99))))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    java.util.Map.of("list_rfps", false),
                    new ModuleSettingsService.MatchUpdate(3))))
        .isNull();
  }

  @Test
  void corruptedStoredGroupFallsBackToDefaults() {
    ModuleSettingEntity row = new ModuleSettingEntity();
    row.setKey(ModuleSettingsService.GROUP_MATCH);
    row.setValue("not json");
    when(repo.findByKey(ModuleSettingsService.GROUP_MATCH)).thenReturn(Optional.of(row));

    assertThat(service.matchTopN()).isEqualTo(5);
  }
}
