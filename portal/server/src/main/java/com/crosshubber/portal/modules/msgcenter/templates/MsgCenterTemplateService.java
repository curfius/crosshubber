package com.crosshubber.portal.modules.msgcenter.templates;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task templates (plan §9 amendment): CRUD + immutable versioning + publish-time resolution.
 *
 * <p>Resolution contract: expand at publish (strict — unknown key/version is a 422; retired
 * versions still resolve), store the expanded shape on the broker, never re-resolve at read. The
 * merged envelope runs through the SAME validator/caps as inline envelopes.
 */
@Service
public class MsgCenterTemplateService {

  private final McTaskTemplateRepository templateRepo;
  private final McTaskTemplateVersionRepository versionRepo;
  private final JsonUtils jsonUtils;
  private final ObjectMapper mapper;

  public MsgCenterTemplateService(
      McTaskTemplateRepository templateRepo,
      McTaskTemplateVersionRepository versionRepo,
      JsonUtils jsonUtils,
      ObjectMapper mapper) {
    this.templateRepo = templateRepo;
    this.versionRepo = versionRepo;
    this.jsonUtils = jsonUtils;
    this.mapper = mapper;
  }

  /** Sender-facing listing: published versions only (discoverability for key+version refs). */
  @Transactional(readOnly = true)
  public List<TemplateRefDto> listPublished() {
    List<TemplateRefDto> out = new ArrayList<>();
    for (McTaskTemplateEntity template : templateRepo.findAll()) {
      if (!template.isLive()) {
        continue;
      }
      for (McTaskTemplateVersionEntity version : versions(template.getId())) {
        if ("published".equals(version.getStatus())) {
          out.add(
              new TemplateRefDto(
                  template.getKey(),
                  template.getName(),
                  version.getVersion(),
                  version.getKind(),
                  version.getCompletion(),
                  fieldNames(version)));
        }
      }
    }
    return out;
  }

  /** Author-facing detail: every version with status (Template Studio history view). */
  @Transactional(readOnly = true)
  public List<TemplateDetailDto> listAll() {
    List<TemplateDetailDto> out = new ArrayList<>();
    for (McTaskTemplateEntity template : templateRepo.findAll()) {
      List<VersionRow> versions = new ArrayList<>();
      for (McTaskTemplateVersionEntity version : versions(template.getId())) {
        versions.add(
            new VersionRow(
                version.getVersion(),
                version.getStatus(),
                version.getKind(),
                version.getCompletion(),
                version.getFieldsJson(),
                version.getSectionsJson(),
                version.getCreatedBy(),
                version.getCreatedAt().toString()));
      }
      out.add(
          new TemplateDetailDto(
              template.getKey(), template.getName(), template.getRetiredAt() != null, versions));
    }
    return out;
  }

  /**
   * Publish-time resolution: returns the expanded task fragment (fields/sections/kind/completion).
   */
  @Transactional
  public ObjectNode resolve(String key, int version, ObjectNode overrides) {
    McTaskTemplateEntity template = templateRepo.findByKey(key).orElse(null);
    if (template == null) {
      throw unprocessable("unknown template key '" + key + "'");
    }
    McTaskTemplateVersionEntity row =
        versionRepo.findByTemplateIdAndVersion(template.getId(), version).orElse(null);
    if (row == null) {
      throw unprocessable("unknown template version " + key + ":" + version);
    }
    ObjectNode expanded = mapper.createObjectNode();
    expanded.put("kind", row.getKind());
    expanded.put("completion", row.getCompletion());
    if (row.getFieldsJson() != null) {
      JsonNode fields = jsonUtils.parseTreeOrNull(row.getFieldsJson());
      if (fields != null) {
        expanded.set("fields", fields);
      }
    }
    if (row.getSectionsJson() != null) {
      JsonNode sections = jsonUtils.parseTreeOrNull(row.getSectionsJson());
      if (sections != null) {
        expanded.set("sections", sections);
      }
    }
    // runtime knobs (completion/completionEvent/expiresAt/claim) may be overridden at publish;
    // retired versions still resolve (old senders keep working) but overrides are refused
    if (overrides != null
        && "retired".equals(row.getStatus())
        && overrides.hasNonNull("completion")) {
      throw unprocessable("template version " + key + ":" + version + " is retired");
    }
    if (overrides != null) {
      for (String allowed : List.of("completion", "completionEvent", "expiresAt", "claim")) {
        if (overrides.has(allowed)) {
          expanded.set(allowed, overrides.get(allowed));
        }
      }
    }
    // template provenance markers for the audit trail
    ObjectNode marker = expanded.putObject("template");
    marker.put("key", key);
    marker.put("version", version);
    return expanded;
  }

  @Transactional
  public McTaskTemplateEntity create(String key, String name, String actorSub) {
    if (!key.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
      throw unprocessable("template key must be kebab-case");
    }
    if (name == null || name.isBlank() || name.length() > 200) {
      throw unprocessable("template name must be 1..200 chars");
    }
    if (templateRepo.findByKey(key).isPresent()) {
      throw conflict("template key already exists");
    }
    McTaskTemplateEntity template = new McTaskTemplateEntity();
    template.setKey(key);
    template.setName(name);
    template.setCreatedBy(actorSub);
    template.setCreatedAt(Instant.now());
    return templateRepo.save(template);
  }

  /** Publishes the next immutable version from the current draft shape. */
  @Transactional
  public McTaskTemplateVersionEntity publishVersion(
      String key,
      String kind,
      String completion,
      JsonNode fields,
      JsonNode sections,
      String actorSub) {
    McTaskTemplateEntity template =
        templateRepo
            .findByKey(key)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "template not found: " + key));
    int next =
        versions(template.getId()).stream().mapToInt(v -> v.getVersion()).max().orElse(0) + 1;
    McTaskTemplateVersionEntity version = new McTaskTemplateVersionEntity();
    version.setTemplateId(template.getId());
    version.setVersion(next);
    version.setKind(kind);
    version.setCompletion(completion);
    if (fields != null && fields.isArray()) {
      version.setFieldsJson(fields.toString());
    } else {
      version.setFieldsJson("[]");
    }
    if (sections != null && sections.isArray()) {
      version.setSectionsJson(sections.toString());
    }
    version.setStatus("published");
    version.setCreatedBy(actorSub);
    version.setCreatedAt(Instant.now());
    return versionRepo.save(version);
  }

  @Transactional
  public void retireVersion(String key, int versionNumber, String actorSub) {
    McTaskTemplateEntity template =
        templateRepo
            .findByKey(key)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "template not found"));
    McTaskTemplateVersionEntity version =
        versionRepo
            .findByTemplateIdAndVersion(template.getId(), versionNumber)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "version not found"));
    version.setStatus("retired");
    versionRepo.save(version);
  }

  /** Delete only while zero published versions (session decision: delete-if-unused). */
  @Transactional
  public void deleteIfUnused(String key, String actorSub) {
    McTaskTemplateEntity template =
        templateRepo
            .findByKey(key)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "template not found"));
    boolean hasPublished =
        versions(template.getId()).stream().anyMatch(v -> "published".equals(v.getStatus()));
    if (hasPublished) {
      throw conflict("template has published versions — retire them instead");
    }
    templateRepo.delete(template);
  }

  /** Version rows for one template (authoring + resolution). */
  List<McTaskTemplateVersionEntity> versions(Long templateId) {
    return versionRepo.findByTemplateIdOrderByVersionDesc(templateId);
  }

  private List<String> fieldNames(McTaskTemplateVersionEntity version) {
    List<String> names = new ArrayList<>();
    JsonNode fields = jsonUtils.parseTreeOrNull(version.getFieldsJson());
    if (fields != null && fields.isArray()) {
      for (JsonNode field : fields) {
        JsonNode name = field.get("name");
        if (name != null && name.isString()) {
          names.add(name.asString());
        }
      }
    }
    return names;
  }

  private static ResponseStatusException unprocessable(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
  }

  private static ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }

  /** Sender-facing ref (published versions only). */
  public record TemplateRefDto(
      String key, String name, int version, String kind, String completion, List<String> fields) {}

  public record VersionRow(
      int version,
      String status,
      String kind,
      String completion,
      String fields,
      String sections,
      String createdBy,
      String createdAt) {}

  public record TemplateDetailDto(
      String key, String name, boolean retired, List<VersionRow> versions) {}
}
