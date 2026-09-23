package com.crosshubber.portal.modules.i18n.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.i18n.I18nService;
import com.crosshubber.portal.modules.i18n.dto.I18nConfigDto;

/** GET /api/i18n/config — public. */
@RestController
public class I18nConfigController {

  private final I18nService i18nService;

  public I18nConfigController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @GetMapping("/api/i18n/config")
  public I18nConfigDto config() {
    return i18nService.getConfig();
  }
}
