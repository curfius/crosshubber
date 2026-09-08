package com.crosshubber.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 configuration (Spring Boot 4 / Framework 7 baseline).
 *
 * <p>Why a custom mapper? Each DTO controls its own null/empty inclusion (mirrors Node's
 * per-endpoint behavior). The global {@code spring.jackson.*} properties in application.yml are
 * dead because this bean backs off auto-configuration.
 *
 * <p>Boot 4 note: defining an {@code ObjectMapper} bean is no longer sufficient to replace the
 * auto-configured mapper — a {@link JsonMapper} bean is required. {@link JsonMapper} extends {@code
 * ObjectMapper}, so injection points typed {@code ObjectMapper} still resolve. Features below are
 * Jackson 2-parity settings, pinned explicitly regardless of Jackson 3 defaults: ISO-8601 dates and
 * unknown-property leniency (both are Jackson 3 defaults too, but the JSON contract tests guard
 * them either way).
 */
@Configuration
public class JacksonConfig {

  @Bean
  public JsonMapper jsonMapper() {
    return JsonMapper.builder()
        .findAndAddModules()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }
}
