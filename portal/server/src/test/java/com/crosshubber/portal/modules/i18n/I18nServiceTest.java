package com.crosshubber.portal.modules.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelRepository;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageEntity;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageRepository;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsRepository;

class I18nServiceTest {

  private final I18nLanguageRepository languageRepo = Mockito.mock(I18nLanguageRepository.class);
  private final I18nLabelRepository labelRepo = Mockito.mock(I18nLabelRepository.class);
  private final I18nSettingsRepository settingsRepo = Mockito.mock(I18nSettingsRepository.class);
  private final I18nService svc =
      new I18nService(
          languageRepo, labelRepo, settingsRepo, new JsonUtils(new JacksonConfig().jsonMapper()));

  private static I18nLanguageEntity language(String code, int order) {
    I18nLanguageEntity l = new I18nLanguageEntity();
    l.setCode(code);
    l.setName(code);
    l.setNativeName(code);
    l.setEnabled(true);
    l.setSeeded(true);
    l.setSortOrder(order);
    return l;
  }

  @Test
  void languageDtoMapsAllKeysAlwaysPresent() {
    I18nLanguageEntity l = language("en", 1);
    var dto = I18nService.languageDto(l);

    assertEquals("en", dto.code());
    assertEquals("en", dto.name());
    assertEquals("en", dto.nativeName());
    assertEquals(true, dto.enabled());
    assertEquals(true, dto.seeded());
    assertEquals(1, dto.sortOrder());
  }

  @Test
  void getConfigReturnsFullContractShape() {
    I18nSettingsEntity settings = new I18nSettingsEntity();
    settings.setId(1);
    settings.setDefaultLanguage("en");
    settings.setFallbackLanguage("en");
    settings.setOverrides("{\"timezone\":\"UTC\"}");
    settings.setContentVersion(7);
    Mockito.when(settingsRepo.findById(1)).thenReturn(java.util.Optional.of(settings));
    Mockito.when(languageRepo.findAll(Mockito.any(org.springframework.data.domain.Sort.class)))
        .thenReturn(List.of(language("en", 1), language("de", 2)));

    var config = svc.getConfig();

    assertEquals(2, config.languages().size());
    assertEquals("en", config.defaultLanguage());
    assertEquals("en", config.fallbackLanguage());
    assertEquals("UTC", config.overrides().get("timezone"));
    assertEquals(7, config.contentVersion());
  }
}
