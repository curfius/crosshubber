package com.crosshubber.portal.modules.msgcenter.templates;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.SecurityUtils;

import tools.jackson.databind.JsonNode;

/**
 * Template endpoints (plan §6 amendment): authoring CRUD under the dedicated role plus a read-only
 * published listing for senders (key+version discoverability).
 */
@RestController
public class MsgCenterTemplateController {

  private final MsgCenterTemplateService service;

  public MsgCenterTemplateController(MsgCenterTemplateService service) {
    this.service = service;
  }

  /** Read-only listing of published template versions — sender discoverability. */
  @GetMapping("/api/msgcenter/templates")
  public Map<String, Object> listPublished() {
    return Map.of("templates", service.listPublished());
  }

  /** Author listing with full version history (Template Studio). */
  @GetMapping("/api/msgcenter/admin/templates")
  @PreAuthorize("hasRole('portal-msgcenter-templates')")
  public Map<String, Object> listAll() {
    return Map.of("templates", service.listAll());
  }

  @PostMapping("/api/msgcenter/admin/templates")
  @PreAuthorize("hasRole('portal-msgcenter-templates')")
  public Map<String, Object> create(@RequestBody JsonNode body) {
    var created =
        service.create(
            stringOf(body, "key"), stringOf(body, "name"), SecurityUtils.currentUserSub());
    return Map.of("key", created.getKey(), "name", created.getName());
  }

  /** Publishes the next immutable version from the builder's form shape. */
  @PostMapping("/api/msgcenter/admin/templates/{key}/versions")
  @PreAuthorize("hasRole('portal-msgcenter-templates')")
  public Map<String, Object> publishVersion(
      @PathVariable("key") String key, @RequestBody JsonNode body) {
    var version =
        service.publishVersion(
            key,
            stringOf(body, "kind") == null ? "collect" : stringOf(body, "kind"),
            stringOf(body, "completion") == null ? "any" : stringOf(body, "completion"),
            body.get("fields"),
            body.get("sections"),
            SecurityUtils.currentUserSub());
    return Map.of("version", version.getVersion(), "status", version.getStatus());
  }

  @PostMapping("/api/msgcenter/admin/templates/{key}/versions/{version}/retire")
  @PreAuthorize("hasRole('portal-msgcenter-templates')")
  public void retireVersion(@PathVariable("key") String key, @PathVariable int version) {
    service.retireVersion(key, version, SecurityUtils.currentUserSub());
  }

  /** Delete only while zero published versions (session decision). */
  @DeleteMapping("/api/msgcenter/admin/templates/{key}")
  @PreAuthorize("hasRole('portal-msgcenter-templates')")
  public void deleteIfUnused(@PathVariable("key") String key) {
    service.deleteIfUnused(key, SecurityUtils.currentUserSub());
  }

  private static String stringOf(JsonNode body, String key) {
    JsonNode node = body == null ? null : body.get(key);
    return node != null && node.isString() ? node.asString() : null;
  }
}
