package com.crosshubber.portal.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeysTest {

  // --- KEY_RE ---

  @Test
  void keyRegexAcceptsKebabCaseKeys() {
    assertTrue("a".matches(Keys.KEY_RE));
    assertTrue("portal-navigation".matches(Keys.KEY_RE));
    assertTrue("a1-b2".matches(Keys.KEY_RE));
    // The pattern is permissive: internal/trailing hyphens pass, only the first char is strict.
    assertTrue("abc-".matches(Keys.KEY_RE));
    assertFalse("".matches(Keys.KEY_RE));
    assertFalse("-abc".matches(Keys.KEY_RE));
    assertFalse("Abc".matches(Keys.KEY_RE));
    assertFalse("a b".matches(Keys.KEY_RE));
    assertFalse("a".repeat(65).matches(Keys.KEY_RE));
    assertTrue("a".repeat(64).matches(Keys.KEY_RE));
  }

  // --- LANG_CODE_RE ---

  @Test
  void langCodeRegexIsCaseInsensitiveWithSubtags() {
    assertTrue("en".matches(Keys.LANG_CODE_RE));
    assertTrue("EN".matches(Keys.LANG_CODE_RE));
    assertTrue("pt-BR".matches(Keys.LANG_CODE_RE));
    assertTrue("zh-Hant-TW".matches(Keys.LANG_CODE_RE));
    assertFalse("e".matches(Keys.LANG_CODE_RE));
    assertFalse("en-".matches(Keys.LANG_CODE_RE));
    assertFalse("1n".matches(Keys.LANG_CODE_RE));
  }

  // --- REF_RE ---

  @Test
  void refRegexRequiresModuleEntryShape() {
    assertTrue("portal-navigation:portal".matches(Keys.REF_RE));
    assertFalse("portal-navigation".matches(Keys.REF_RE));
    assertFalse("portal-navigation:".matches(Keys.REF_RE));
    assertFalse("Portal:a".matches(Keys.REF_RE));
  }

  // --- I18N_KEY_RE ---

  @Test
  void i18nKeyRegexRequiresSegmentedKeys() {
    assertTrue("app.section.label".matches(Keys.I18N_KEY_RE));
    assertTrue("nav-dashboard.title".matches(Keys.I18N_KEY_RE));
    assertTrue("portal-shell.doc-title-2".matches(Keys.I18N_KEY_RE));
    assertFalse("single".matches(Keys.I18N_KEY_RE));
    assertFalse(".leading".matches(Keys.I18N_KEY_RE));
    assertFalse("trailing.".matches(Keys.I18N_KEY_RE));
  }

  // --- ELEMENT_RE ---

  @Test
  void elementRegexAcceptsCustomElementNames() {
    assertTrue("my-element".matches(Keys.ELEMENT_RE));
    assertFalse("1element".matches(Keys.ELEMENT_RE));
    assertFalse("-element".matches(Keys.ELEMENT_RE));
  }

  // --- UUID_PATTERN ---

  @Test
  void uuidPatternIsCaseInsensitive() {
    assertTrue(Keys.UUID_PATTERN.matcher("123e4567-e89b-12d3-a456-426614174000").matches());
    assertTrue(Keys.UUID_PATTERN.matcher("123E4567-E89B-12D3-A456-426614174000").matches());
    assertFalse(Keys.UUID_PATTERN.matcher("not-a-uuid").matches());
    assertFalse(Keys.UUID_PATTERN.matcher("123e4567e89b12d3a456426614174000").matches());
  }

  // --- vocabularies ---

  @Test
  void typeVocabularyMatchesApiContract() {
    assertEquals(4, Keys.TYPES.size());
    assertTrue(Keys.TYPES.contains("mfe"));
  }
}
