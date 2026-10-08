package com.crosshubber.portal.modules.msgcenter.groups;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.msgcenter.groups.McGroupDtos.GroupDto;

import tools.jackson.databind.JsonNode;

/**
 * Group endpoints (plan §6 amendment): self-service browse/join/leave plus role-gated creation and
 * owner-curated membership management.
 */
@RestController
public class MsgCenterGroupController {

  private final MsgCenterGroupService service;

  public MsgCenterGroupController(MsgCenterGroupService service) {
    this.service = service;
  }

  /** All live groups (visibility metadata included; member counts included). */
  @GetMapping("/api/msgcenter/groups")
  public java.util.Map<String, Object> browse() {
    return java.util.Map.of("groups", service.browse(SecurityUtils.currentUserSub()));
  }

  /** My memberships with per-group email flags (user settings "My groups" card). */
  @GetMapping("/api/msgcenter/my-groups")
  public java.util.Map<String, Object> myGroups() {
    return java.util.Map.of("groups", service.myGroups(SecurityUtils.currentUserSub()));
  }

  /** Create a group — dedicated role (plan decision 14). */
  @PostMapping("/api/msgcenter/groups")
  @PreAuthorize("hasRole('portal-msgcenter-groups')")
  public java.util.Map<String, Object> create(@RequestBody JsonNode body) {
    GroupDto created =
        service.create(
            stringOf(body, "key"),
            stringOf(body, "name"),
            body.get("visibility") == null ? "open" : stringOf(body, "visibility"),
            SecurityUtils.currentUserSub());
    return java.util.Map.of("group", created);
  }

  @PostMapping("/api/msgcenter/groups/{key}/retire")
  @PreAuthorize("hasRole('portal-msgcenter-groups')")
  public void retire(@PathVariable("key") String key) {
    service.retire(key, SecurityUtils.currentUserSub());
  }

  @PostMapping("/api/msgcenter/groups/{key}/join")
  public void join(@PathVariable("key") String key) {
    service.join(key, SecurityUtils.currentUserSub(), displayName());
  }

  /** Leaving is unconditional — the user-side unsubscribe primitive. */
  @PostMapping("/api/msgcenter/groups/{key}/leave")
  public void leave(@PathVariable("key") String key) {
    service.leave(key, SecurityUtils.currentUserSub(), SecurityUtils.currentUserSub());
  }

  /** Owner/admin adds a member (closed groups are managed this way). */
  @PostMapping("/api/msgcenter/groups/{key}/members")
  @PreAuthorize("hasAnyRole('portal-msgcenter-edit')")
  public void addMember(@PathVariable("key") String key, @RequestBody JsonNode body) {
    service.addMember(key, stringOf(body, "userSub"), SecurityUtils.currentUserSub(), true);
  }

  @DeleteMapping("/api/msgcenter/groups/{key}/members/{userSub}")
  @PreAuthorize("hasAnyRole('portal-msgcenter-edit')")
  public void removeMember(
      @PathVariable("key") String key, @PathVariable("userSub") String userSub) {
    service.removeMember(key, userSub, SecurityUtils.currentUserSub(), true);
  }

  @PostMapping("/api/msgcenter/groups/{key}/owners")
  @PreAuthorize("hasAnyRole('portal-msgcenter-edit','portal-msgcenter-groups')")
  public void grantOwner(@PathVariable("key") String key, @RequestBody JsonNode body) {
    String target = stringOf(body, "userSub");
    service.grantOwner(key, target, SecurityUtils.currentUserSub(), true);
  }

  @DeleteMapping("/api/msgcenter/groups/{key}/owners/{userSub}")
  @PreAuthorize("hasAnyRole('portal-msgcenter-edit','portal-msgcenter-groups')")
  public void revokeOwner(
      @PathVariable("key") String key, @PathVariable("userSub") String userSub) {
    service.revokeOwner(key, userSub, SecurityUtils.currentUserSub(), true);
  }

  /** Per-group email opt-in (Phase 7 channel); flag is persisted from day one. */
  @PutMapping("/api/msgcenter/my-groups/{key}/email-flag")
  public void setEmailFlag(@PathVariable String key, @RequestBody JsonNode body) {
    boolean enabled = body.get("enabled") != null && body.get("enabled").asBoolean();
    service.setEmailFlag(key, SecurityUtils.currentUserSub(), enabled);
  }

  private static String stringOf(JsonNode body, String key) {
    JsonNode node = body == null ? null : body.get(key);
    return node != null && node.isString() ? node.asString() : null;
  }

  private static String displayName() {
    var principal = SecurityUtils.principal();
    return principal != null ? principal.name() : null;
  }
}
