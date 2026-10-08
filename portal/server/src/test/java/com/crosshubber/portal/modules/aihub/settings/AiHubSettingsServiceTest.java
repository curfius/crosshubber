package com.crosshubber.portal.modules.aihub.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;

class AiHubSettingsServiceTest {

  private final JsonUtils jsonUtils = new JsonUtils(new JacksonConfig().jsonMapper());
  private final AiHubSettingsRepository repo = mock(AiHubSettingsRepository.class);
  private final AiHubSettingsService service = new AiHubSettingsService(repo, jsonUtils);

  @Test
  void getReturnsEmptyMapWhenRowAbsent() {
    when(repo.findById(AiHubSettingsService.ROW_ID)).thenReturn(Optional.empty());

    assertEquals(Map.of(), service.get());
  }

  @Test
  void updateMergesIntoExistingRow() {
    AiHubSettingsEntity entity = new AiHubSettingsEntity();
    entity.setSettings("{\"systemPrompt\":\"be helpful\",\"temperature\":0.7}");
    when(repo.findById(AiHubSettingsService.ROW_ID)).thenReturn(Optional.of(entity));

    Map<String, Object> merged = service.update(Map.of("temperature", 0.3));

    assertEquals(0.3, merged.get("temperature"));
    assertEquals("be helpful", merged.get("systemPrompt"), "existing keys survive the merge");
    String stored = entity.getSettings();
    assertTrue(stored.contains("\"temperature\":0.3"), "new value stored: " + stored);
    assertTrue(stored.contains("\"systemPrompt\""), "old value stored: " + stored);
  }

  @Test
  void updateCreatesRowWhenAbsent() {
    when(repo.findById(AiHubSettingsService.ROW_ID)).thenReturn(Optional.empty());

    Map<String, Object> merged = service.update(Map.of("maxTokens", 2048));

    assertEquals(2048, merged.get("maxTokens"));
    ArgumentCaptor<AiHubSettingsEntity> captor = ArgumentCaptor.forClass(AiHubSettingsEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(AiHubSettingsService.ROW_ID, captor.getValue().getId());
    assertEquals(2048, jsonUtils.parseMap(captor.getValue().getSettings()).get("maxTokens"));
  }
}
