package com.crosshubber.portal.modules.msgcenter.groups;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.modules.msgcenter.groups.McGroupDtos.GroupDto;
import com.crosshubber.portal.modules.msgcenter.groups.McGroupDtos.MembershipDto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Group lifecycle + membership rules (amendment decisions 13/14):
 *
 * <ul>
 *   <li>create/retire: {@code portal-msgcenter-groups} role (enforced in the controller)
 *   <li>join: self-service on open groups only; closed groups → owner action
 *   <li>leave: always allowed for the member themselves (the unsubscribe primitive)
 *   <li>add/remove others: owners (or admins, controller-enforced)
 *   <li>owner notifications for all churn, published as msgcenter messages AFTER commit
 * </ul>
 */
@Service
public class MsgCenterGroupService {

  private final McGroupRepository groupRepo;
  private final McGroupOwnerRepository ownerRepo;
  private final McGroupMemberRepository memberRepo;
  private final EventPublisher eventPublisher;
  private final ObjectMapper mapper;

  public MsgCenterGroupService(
      McGroupRepository groupRepo,
      McGroupOwnerRepository ownerRepo,
      McGroupMemberRepository memberRepo,
      EventPublisher eventPublisher,
      ObjectMapper mapper) {
    this.groupRepo = groupRepo;
    this.ownerRepo = ownerRepo;
    this.memberRepo = memberRepo;
    this.eventPublisher = eventPublisher;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public List<GroupDto> browse(String viewerSub) {
    return groupRepo
        .findAll(
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name")))
        .stream()
        .filter(g -> g.isLive())
        .map(g -> toDto(g, viewerSub))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<MembershipDto> myGroups(String userSub) {
    return memberRepo.findByUserSub(userSub).stream()
        .map(
            membership ->
                groupRepo
                    .findById(membership.getGroupId())
                    .filter(g -> g.isLive())
                    .map(
                        g ->
                            new MembershipDto(
                                g.getKey(),
                                g.getName(),
                                membership.isEmailFlag(),
                                isOwner(g.getId(), userSub)))
                    .orElse(null))
        .filter(m -> m != null)
        .toList();
  }

  @Transactional
  public GroupDto create(String key, String name, String visibility, String creatorSub) {
    if (!key.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
      throw badRequest("group key must be kebab-case");
    }
    if (name == null || name.isBlank() || name.length() > 100) {
      throw badRequest("group name must be 1..100 chars");
    }
    if (!"open".equals(visibility) && !"closed".equals(visibility)) {
      throw badRequest("visibility must be open|closed");
    }
    if (groupRepo.findByKey(key).isPresent()) {
      throw conflict("group key already exists");
    }
    McGroupEntity group = new McGroupEntity();
    group.setKey(key);
    group.setName(name);
    group.setVisibility(visibility);
    group.setCreatedBy(creatorSub);
    group.setCreatedAt(Instant.now());
    groupRepo.save(group);
    ownerRepo.save(new McGroupOwnerEntity(group.getId(), creatorSub));
    return toDto(group, creatorSub);
  }

  @Transactional
  public void retire(String key, String actorSub) {
    McGroupEntity group = live(key);
    group.setRetiredAt(Instant.now());
    groupRepo.save(group);
  }

  /** Self-service join. Closed groups refuse non-owners (403); duplicates are no-ops. */
  @Transactional
  public void join(String key, String userSub, String actorName) {
    McGroupEntity group = live(key);
    if (memberRepo.findByGroupIdAndUserSub(group.getId(), userSub).isPresent()) {
      return;
    }
    if ("closed".equals(group.getVisibility()) && !isOwner(group.getId(), userSub)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "closed group — ask an owner");
    }
    memberRepo.save(new McGroupMemberEntity(group.getId(), userSub, "self"));
    notifyOwners(group, userSub, "join", null);
  }

  /** The unsubscribe primitive: leaving is never blocked. Owner-side removals use removeMember. */
  @Transactional
  public void leave(String key, String userSub, String actorSub) {
    McGroupEntity group = live(key);
    McGroupMemberEntity membership =
        memberRepo.findByGroupIdAndUserSub(group.getId(), userSub).orElse(null);
    if (membership == null) {
      return;
    }
    memberRepo.delete(membership);
    notifyOwners(group, userSub, "leave", null);
  }

  /** Owner/admin action: direct add (any visibility). Notifies the target user. */
  @Transactional
  public void addMember(String key, String targetSub, String actorSub, boolean byAdmin) {
    McGroupEntity group = live(key);
    requireOwner(group, actorSub, byAdmin);
    if (memberRepo.findByGroupIdAndUserSub(group.getId(), targetSub).isPresent()) {
      return;
    }
    McGroupMemberEntity member = new McGroupMemberEntity(group.getId(), targetSub, actorSub);
    memberRepo.save(member);
    notifyTarget(group, targetSub, "added", actorSub);
    notifyOwners(group, targetSub, "added", actorSub);
  }

  /** Owner/admin removal; the affected user is notified. */
  @Transactional
  public void removeMember(String key, String targetSub, String actorSub, boolean byAdmin) {
    McGroupEntity group = live(key);
    requireOwner(group, actorSub, byAdmin);
    McGroupMemberEntity membership =
        memberRepo.findByGroupIdAndUserSub(group.getId(), targetSub).orElse(null);
    if (membership == null) {
      return;
    }
    memberRepo.delete(membership);
    notifyTarget(group, targetSub, "removed", actorSub);
    notifyOwners(group, targetSub, "removed", actorSub);
  }

  @Transactional
  public void grantOwner(String key, String targetSub, String actorSub, boolean byAdmin) {
    McGroupEntity group = live(key);
    requireOwner(group, actorSub, byAdmin);
    if (ownerRepo.findByGroupIdAndUserSub(group.getId(), targetSub).isPresent()) {
      return;
    }
    ownerRepo.save(new McGroupOwnerEntity(group.getId(), targetSub));
    notifyTarget(group, targetSub, "owner-granted", actorSub);
  }

  @Transactional
  public void revokeOwner(String key, String targetSub, String actorSub, boolean byAdmin) {
    McGroupEntity group = live(key);
    requireOwner(group, actorSub, byAdmin);
    ownerRepo.findByGroupIdAndUserSub(group.getId(), targetSub).ifPresent(ownerRepo::delete);
    notifyTarget(group, targetSub, "owner-revoked", actorSub);
  }

  @Transactional
  public void setEmailFlag(String key, String userSub, boolean enabled) {
    McGroupEntity group = live(key);
    McGroupMemberEntity membership =
        memberRepo
            .findByGroupIdAndUserSub(group.getId(), userSub)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not a member"));
    membership.setEmailFlag(enabled);
    memberRepo.save(membership);
  }

  private GroupDto toDto(McGroupEntity group, String viewerSub) {
    List<String> owners =
        ownerRepo.findByGroupId(group.getId()).stream().map(o -> o.getUserSub()).toList();
    return new GroupDto(
        group.getId(),
        group.getKey(),
        group.getName(),
        group.getVisibility(),
        group.getRetiredAt() != null,
        owners,
        memberRepo.findByGroupId(group.getId()).size(),
        memberRepo.findByGroupIdAndUserSub(group.getId(), viewerSub).isPresent(),
        owners.contains(viewerSub),
        memberRepo
            .findByGroupIdAndUserSub(group.getId(), viewerSub)
            .map(m -> m.isEmailFlag())
            .orElse(false));
  }

  private McGroupEntity live(String key) {
    McGroupEntity group = groupRepo.findByKey(key).orElse(null);
    if (group == null || !group.isLive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "group not found: " + key);
    }
    return group;
  }

  private void requireOwner(McGroupEntity group, String actorSub, boolean byAdmin) {
    if (byAdmin || isOwner(group.getId(), actorSub)) {
      return;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only group owners may do that");
  }

  private boolean isOwner(Long groupId, String userSub) {
    if (userSub == null) {
      return false;
    }
    return ownerRepo.findByGroupIdAndUserSub(groupId, userSub).isPresent();
  }

  /**
   * Owner churn notifications — msgcenter messages on {@code portal.msg.msgcenter.group.>}, one to
   * each owner; called INSIDE the transactional boundary but publishes after commit via the event
   * publisher's fail-soft semantics.
   */
  private void notifyOwners(McGroupEntity group, String targetSub, String event, String actorSub) {
    publishMembershipEvent(group, targetSub, event, actorSub, ownerSubs(group.getId()));
  }

  private void notifyTarget(McGroupEntity group, String targetSub, String event, String actorSub) {
    publishMembershipEvent(group, targetSub, event, actorSub, List.of(targetSub));
  }

  private void publishMembershipEvent(
      McGroupEntity group,
      String targetSub,
      String event,
      String actorSub,
      List<String> recipients) {
    if (recipients.isEmpty() || !eventPublisher.isEnabled()) {
      return;
    }
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("v", 1);
    envelope.put("type", "notification");
    envelope.put("moduleKey", "msgcenter");
    envelope.put("id", java.util.UUID.randomUUID().toString());
    envelope.put("createdAt", Instant.now().toString());
    ObjectNode audience = envelope.putObject("audience");
    var users = audience.putArray("users");
    recipients.forEach(users::add);
    envelope.putObject("title").put("en", "Group membership change");
    envelope
        .putObject("body")
        .put(
            "en",
            "Group '"
                + group.getKey()
                + "': member '"
                + targetSub
                + "' "
                + event
                + (actorSub == null ? "" : " (by " + actorSub + ")"));
    try {
      eventPublisher.publish("portal.msg.msgcenter.group." + event, envelope.toString());
    } catch (Exception e) {
      // fail-soft: notification loss is acceptable; membership state is authoritative
    }
  }

  private List<String> ownerSubs(Long groupId) {
    return ownerRepo.findByGroupId(groupId).stream().map(o -> o.getUserSub()).toList();
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  private static ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }
}
