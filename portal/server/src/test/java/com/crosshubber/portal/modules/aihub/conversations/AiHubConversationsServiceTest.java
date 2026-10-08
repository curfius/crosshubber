package com.crosshubber.portal.modules.aihub.conversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

class AiHubConversationsServiceTest {

  private final AiHubConversationRepository repo = mock(AiHubConversationRepository.class);
  private final ChatMemory chatMemory = mock(ChatMemory.class);
  private final AiHubConversationsService service = new AiHubConversationsService(repo, chatMemory);

  private AiHubConversationEntity conversation(String title) {
    AiHubConversationEntity c = new AiHubConversationEntity();
    c.setId("conv_1");
    c.setUserId("u1");
    c.setTitle(title);
    return c;
  }

  @Test
  void updateConversationRenamesAndPins() {
    AiHubConversationEntity conv = conversation("Old title");
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.of(conv));

    var dto = service.updateConversation("conv_1", "u1", "  New title  ", true);

    assertEquals("New title", dto.title());
    assertTrue(dto.pinned());
    assertEquals("New title", conv.getTitle());
    assertTrue(conv.isPinned());
    verify(repo).saveAndFlush(conv);
  }

  @Test
  void updateConversationIgnoresBlankTitleAndKeepsPinnedUnchanged() {
    AiHubConversationEntity conv = conversation("Old title");
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.of(conv));

    var dto = service.updateConversation("conv_1", "u1", "   ", null);

    assertEquals("Old title", dto.title());
    assertFalse(dto.pinned());
    assertEquals("Old title", conv.getTitle());
    verify(repo).saveAndFlush(conv);
  }

  @Test
  void updateConversationUnpinsWithoutTouchingTitle() {
    AiHubConversationEntity conv = conversation("Old title");
    conv.setPinned(true);
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.of(conv));

    var dto = service.updateConversation("conv_1", "u1", null, false);

    assertEquals("Old title", dto.title());
    assertFalse(dto.pinned());
    verify(repo).saveAndFlush(conv);
  }

  @Test
  void updateConversationReturnsNullWhenNotOwned() {
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.empty());

    assertNull(service.updateConversation("conv_1", "u1", "New title", true));
    verify(repo, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verifyNoMoreInteractions(chatMemory);
  }

  @Test
  void deleteConversationClearsMemoryBeforeDeletingMetadata() {
    AiHubConversationEntity conv = conversation("Old title");
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.of(conv));

    assertTrue(service.deleteConversation("conv_1", "u1"));
    verify(chatMemory).clear("conv_1");
    verify(repo).delete(conv);
  }

  @Test
  void deleteConversationReturnsFalseWhenNotOwned() {
    when(repo.findByIdAndUserId("conv_1", "u1")).thenReturn(Optional.empty());

    assertFalse(service.deleteConversation("conv_1", "u1"));
    verify(chatMemory, never()).clear(org.mockito.ArgumentMatchers.anyString());
    verify(repo, never()).delete(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void toConversationDtoExposesPinFlag() {
    AiHubConversationEntity conv = conversation("T");
    conv.setUserId("u1");
    conv.setPinned(true);

    var dto = service.toConversationDto(conv);

    assertTrue(dto.pinned());
    assertEquals("T", dto.title());
    assertEquals("u1", dto.userId());
  }
}
