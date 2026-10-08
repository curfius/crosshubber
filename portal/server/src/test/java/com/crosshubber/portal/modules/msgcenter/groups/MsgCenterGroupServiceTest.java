package com.crosshubber.portal.modules.msgcenter.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.common.events.EventPublisher.PublishResult;

import tools.jackson.databind.ObjectMapper;

class MsgCenterGroupServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McGroupRepository groupRepo = mock(McGroupRepository.class);
  private final McGroupOwnerRepository ownerRepo = mock(McGroupOwnerRepository.class);
  private final McGroupMemberRepository memberRepo = mock(McGroupMemberRepository.class);
  private final EventPublisher publisher = mock(EventPublisher.class);

  private MsgCenterGroupService service;
  private McGroupEntity group;
  private long nextId = 10;

  @BeforeEach
  void setUp() {
    service = new MsgCenterGroupService(groupRepo, ownerRepo, memberRepo, publisher, MAPPER);
    group = new McGroupEntity();
    setEntityId(group, 1L);
    group.setKey("back-office");
    group.setName("Back Office");
    group.setVisibility("closed");
    group.setCreatedAt(Instant.now());
    when(groupRepo.findByKey("back-office")).thenReturn(Optional.of(group));
    when(groupRepo.findById(1L)).thenReturn(Optional.of(group));
    when(groupRepo.findAll(Sort.by(Sort.Order.asc("name")))).thenReturn(List.of(group));
    lenient().when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(ownerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(publisher.publish(any(), any())).thenReturn(new PublishResult(true, 1));
  }

  @Test
  void joinOpenGroupSelfServiceAndOwnersNotified() {
    group.setVisibility("open");
    when(memberRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.empty());
    when(ownerRepo.findByGroupId(1L)).thenReturn(List.of());
    service.join("back-office", "u1", "U One");
    org.mockito.Mockito.verify(memberRepo).save(any());
    org.mockito.Mockito.verify(publisher, org.mockito.Mockito.never()).publish(any(), any());
  }

  @Test
  void closedGroupJoinRefusedWithoutOwnership() {
    when(memberRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.empty());
    when(ownerRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.empty());
    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.join("back-office", "u1", "U"));
    assertEquals(403, e.getStatusCode().value());
  }

  @Test
  void closedGroupJoinAllowedForOwner() {
    when(memberRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.empty());
    when(ownerRepo.findByGroupIdAndUserSub(1L, "u1"))
        .thenReturn(Optional.of(new McGroupOwnerEntity(1L, "u1")));
    when(ownerRepo.findByGroupId(1L)).thenReturn(List.of(new McGroupOwnerEntity(1L, "u1")));
    service.join("back-office", "u1", "U");
    org.mockito.Mockito.verify(memberRepo).save(any());
  }

  @Test
  void leaveAlwaysAllowedAndOwnersNotified() {
    McGroupMemberEntity membership = new McGroupMemberEntity(1L, "u1", "self");
    when(memberRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.of(membership));
    when(ownerRepo.findByGroupId(1L)).thenReturn(List.of(new McGroupOwnerEntity(1L, "o1")));
    when(publisher.isEnabled()).thenReturn(true);
    service.leave("back-office", "u1", "u1");
    org.mockito.Mockito.verify(memberRepo).delete(membership);
    // one churn notification to the owner
    org.mockito.Mockito.verify(publisher)
        .publish(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
  }

  @Test
  void ownerNotificationsAreSentForAdd() {
    when(memberRepo.findByGroupIdAndUserSub(1L, "u1")).thenReturn(Optional.empty());
    when(ownerRepo.findByGroupIdAndUserSub(1L, "o1"))
        .thenReturn(Optional.of(new McGroupOwnerEntity(1L, "o1")));
    when(ownerRepo.findByGroupId(1L)).thenReturn(List.of(new McGroupOwnerEntity(1L, "o1")));
    when(publisher.isEnabled()).thenReturn(true);
    service.addMember("back-office", "u1", "o1", false);
    // target notified + owner notified
    org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(2))
        .publish(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
  }

  @Test
  void nonOwnerCannotAddMembers() {
    when(ownerRepo.findByGroupIdAndUserSub(1L, "stranger")).thenReturn(Optional.empty());
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () -> service.addMember("back-office", "u1", "stranger", false));
    assertEquals(403, e.getStatusCode().value());
  }

  @Test
  void createValidatesKeyVisibilityAndUniqueness() {
    ResponseStatusException badKey =
        assertThrows(
            ResponseStatusException.class,
            () -> service.create("Bad Key!", "n", "open", "creator"));
    assertEquals(400, badKey.getStatusCode().value());
    ResponseStatusException clash =
        assertThrows(
            ResponseStatusException.class,
            () -> service.create("back-office", "n", "open", "creator"));
    assertEquals(409, clash.getStatusCode().value());
    when(groupRepo.findByKey("fresh-group")).thenReturn(Optional.empty());
    lenient()
        .when(groupRepo.save(any()))
        .thenAnswer(
            inv -> {
              McGroupEntity saved = inv.getArgument(0);
              setEntityId(saved, nextId++);
              return saved;
            });
    var created = service.create("fresh-group", "Fresh", "open", "creator");
    assertEquals("fresh-group", created.key());
    assertFalse(created.retired());
  }

  @Test
  void browseSkipsRetiredGroups() {
    group.setRetiredAt(Instant.now());
    assertEquals(0, service.browse("v").size());
  }

  private static void setEntityId(Object entity, long id) {
    try {
      var field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
