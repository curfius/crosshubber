package com.crosshubber.portal.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * i18n seed catalog — mirrors {@code portal/src/shared/i18n-catalog.ts}.
 *
 * <p>The full language/label inventory is shipped as {@code i18n-catalog.json} on the classpath and
 * parsed once at class-load. Seeding semantics (applied by the Reconciler): new languages/keys
 * install on deploy; admin-edited rows are never clobbered.
 */
public final class I18nCatalog {

  private I18nCatalog() {}

  public record Language(
      String code,
      String name,
      String nativeName,
      boolean enabled,
      boolean seeded,
      int sortOrder) {}

  public static final String DEFAULT_LANGUAGE;
  public static final List<Language> LANGUAGES;
  public static final Map<String, Map<String, String>> LABELS;

  static {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in = I18nCatalog.class.getResourceAsStream("/i18n-catalog.json")) {
      if (in == null) {
        throw new IllegalStateException("i18n-catalog.json missing from classpath");
      }
      Map<String, Object> root =
          mapper.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
      DEFAULT_LANGUAGE = (String) root.get("defaultLanguage");

      List<Map<String, Object>> languages =
          mapper.convertValue(
              root.get("languages"), new TypeReference<List<Map<String, Object>>>() {});
      LANGUAGES =
          languages.stream()
              .map(
                  l ->
                      new Language(
                          (String) l.get("code"),
                          (String) l.get("name"),
                          (String) l.get("nativeName"),
                          true,
                          true,
                          ((Number) l.get("sortOrder")).intValue()))
              .toList();

      LinkedHashMap<String, LinkedHashMap<String, String>> parsed =
          mapper.convertValue(
              root.get("labels"),
              new TypeReference<LinkedHashMap<String, LinkedHashMap<String, String>>>() {});
      Map<String, Map<String, String>> labels = new LinkedHashMap<>();
      labels.putAll(parsed);
      LABELS = labels;
    } catch (IOException e) {
      throw new IllegalStateException("failed to load i18n catalog", e);
    }
  }
}
