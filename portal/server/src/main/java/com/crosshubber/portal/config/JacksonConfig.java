package com.crosshubber.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson configuration.
 *
 * <p>Note: Spring Boot 3.4 uses tools.jackson (Jackson 3) — register JSR310 for Instant.
 *
 * <p>Why a custom bare mapper? Each DTO controls its own null/empty inclusion (mirrors Node's
 * per-endpoint behavior). The global {@code spring.jackson.*} properties in application.yml are
 * dead because this bean backs off auto-configuration.
 */
@Configuration
public class JacksonConfig {

  @Bean
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return mapper;
  }
}
