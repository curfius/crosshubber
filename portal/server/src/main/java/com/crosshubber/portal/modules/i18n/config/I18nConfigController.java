package com.crosshubber.portal.modules.i18n.config;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.i18n.I18nService;

/** GET /api/i18n/config — public (mirrors i18n.routes.ts public reads). */
@RestController
public class I18nConfigController {

  private final I18nService i18nService;

  public I18nConfigController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @GetMapping("/api/i18n/config")
  public Map<String, Object> config() {
    return i18nService.getConfig();
  }
}
