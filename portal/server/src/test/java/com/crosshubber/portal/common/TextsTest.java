package com.crosshubber.portal.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextsTest {

  // --- string ---

  @Test
  void stringReturnsStringsOnly() {
    assertEquals("abc", Texts.string("abc"));
    assertNull(Texts.string(null));
    assertNull(Texts.string(42));
    assertNull(Texts.string(List.of("a")));
  }

  // --- notBlank / orEmpty ---

  @Test
  void notBlankTreatsWhitespaceAsBlank() {
    assertTrue(Texts.notBlank("x"));
    assertFalse(Texts.notBlank(""));
    assertFalse(Texts.notBlank("   "));
    assertFalse(Texts.notBlank(null));
  }

  @Test
  void orEmptyMapsNullToEmptyString() {
    assertEquals("", Texts.orEmpty(null));
    assertEquals("a", Texts.orEmpty("a"));
  }

  // --- scalar coercions ---

  @Test
  void stringOrFallsBackOnNonString() {
    assertEquals("a", Texts.stringOr("a", "b"));
    assertEquals("b", Texts.stringOr(1, "b"));
    assertEquals("b", Texts.stringOr(null, "b"));
  }

  @Test
  void intOrTruncatesDoubles() {
    assertEquals(3, Texts.intOr(3.9, 0));
    assertEquals(7, Texts.intOr(7, 0));
    assertEquals(5, Texts.intOr("nope", 5));
  }

  @Test
  void boolOrFallsBackOnNonBoolean() {
    assertTrue(Texts.boolOr(true, false));
    assertFalse(Texts.boolOr("true", false));
    assertTrue(Texts.boolOr(null, true));
  }

  // --- joinComma ---

  @Test
  void joinCommaJoinsListsAndPassesThroughStrings() {
    assertEquals("a,b", Texts.joinComma(List.of("a", "b")));
    assertEquals("s", Texts.joinComma("s"));
    assertNull(Texts.joinComma(null));
    assertNull(Texts.joinComma(1));
    assertNull(Texts.joinComma(List.of()));
  }
}
