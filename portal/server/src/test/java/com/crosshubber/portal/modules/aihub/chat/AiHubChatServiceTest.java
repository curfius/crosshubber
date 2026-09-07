package com.crosshubber.portal.modules.aihub.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AiHubChatServiceTest {

  @Test
  void stripVersionSuffixRemovesTrailingV1() {
    assertEquals("https://api.openai.com", AiHubChatService.stripVersionSuffix("https://api.openai.com/v1"));
    assertEquals("https://openrouter.ai/api", AiHubChatService.stripVersionSuffix("https://openrouter.ai/api/v1"));
    assertEquals("http://localhost:11434", AiHubChatService.stripVersionSuffix("http://localhost:11434/v1"));
    assertEquals("https://api.x.ai", AiHubChatService.stripVersionSuffix("https://api.x.ai/v1"));
  }

  @Test
  void stripVersionSuffixRemovesTrailingV1beta() {
    assertEquals("https://generativelanguage.googleapis.com",
        AiHubChatService.stripVersionSuffix("https://generativelanguage.googleapis.com/v1beta"));
  }

  @Test
  void stripVersionSuffixHandlesTrailingSlash() {
    assertEquals("https://api.openai.com", AiHubChatService.stripVersionSuffix("https://api.openai.com/v1/"));
    assertEquals("http://localhost:11434", AiHubChatService.stripVersionSuffix("http://localhost:11434/v1/"));
  }

  @Test
  void stripVersionSuffixNoVersionSuffix() {
    assertEquals("https://api.deepseek.com", AiHubChatService.stripVersionSuffix("https://api.deepseek.com"));
    assertEquals("https://api.anthropic.com", AiHubChatService.stripVersionSuffix("https://api.anthropic.com"));
  }

  @Test
  void stripVersionSuffixNullInput() {
    assertNull(AiHubChatService.stripVersionSuffix(null));
  }
}
