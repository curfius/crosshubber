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

  // --- blankToNull ---

  @Test
  void blankToNullCollapsesNullAndBlanks() {
    assertNull(Texts.blankToNull(null));
    assertNull(Texts.blankToNull(""));
    assertNull(Texts.blankToNull("   "));
    assertEquals("x", Texts.blankToNull("x"));
    assertEquals(" x ", Texts.blankToNull(" x "));
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
