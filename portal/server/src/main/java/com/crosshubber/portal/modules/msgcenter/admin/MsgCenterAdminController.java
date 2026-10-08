package com.crosshubber.portal.modules.msgcenter.admin;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.config.NatsConnectionConfig;
import com.crosshubber.portal.config.NatsConnectionConfig.NatsSupport;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityEntity;
import com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityRepository;
import com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterTaskService;

import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import tools.jackson.databind.JsonNode;

/**
 * Admin surface (plan §6/§9): activity timelines (admin-only audit, session decision),
 * force-release / admin reset overrides, per-module overview counters, DLQ peek.
 */
@RestController
public class MsgCenterAdminController {

  private final McTaskActivityRepository activityRepo;
  private final MsgCenterTaskService taskService;
  private final MsgCenterAdminStats adminStats;

  public MsgCenterAdminController(
      McTaskActivityRepository activityRepo,
      MsgCenterTaskService taskService,
      MsgCenterAdminStats adminStats) {
    this.activityRepo = activityRepo;
    this.taskService = taskService;
    this.adminStats = adminStats;
  }

  /** Per-task timeline (append-only audit; admin-only per session decision). */
  @GetMapping("/api/msgcenter/admin/tasks/{id}/activity")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> activity(@PathVariable long id) {
    List<McTaskActivityEntity> rows = activityRepo.findByMessageIdOrderByCreatedAtDescIdDesc(id);
    return Map.of(
        "activity",
        rows.stream()
            .map(
                r ->
                    AdminActivityDtos.row(
                        r.getId(),
                        r.getActorSub(),
                        r.getActorName(),
                        r.getAction(),
                        r.getDetailJson() == null ? Map.of() : r.getDetailJson(),
                        r.getCreatedAt() == null ? null : r.getCreatedAt().toString()))
            .toList());
  }

  /** Admin force-release of a stuck claim. */
  @PostMapping("/api/msgcenter/admin/tasks/{id}/release")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public void forceRelease(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
    boolean discardDraft =
        body != null && body.get("discardDraft") != null && body.get("discardDraft").asBoolean();
    taskService.release(id, SecurityUtils.currentUserSub(), displayName(), discardDraft, true);
  }

  /** Admin reset of a stuck task (claim-mode; done stays terminal). */
  @PostMapping("/api/msgcenter/admin/tasks/{id}/reset")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public void reset(@PathVariable long id) {
    taskService.reset(id, SecurityUtils.currentUserSub(), displayName(), true);
  }

  /** Per-module counters for the admin overview. */
  @GetMapping("/api/msgcenter/admin/overview")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> overview() {
    return Map.of("perModule", adminStats.perModule());
  }

  /**
   * DLQ peek — an ephemeral non-ack consumer reads the last N DLQ entries without acking (they stay
   * in the stream for real ops tooling). Empty when NATS is disabled.
   */
  @GetMapping("/api/msgcenter/admin/dlq")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> dlq() {
    return Map.of("entries", adminStats.dlqPeek(20));
  }

  private static String displayName() {
    var principal = SecurityUtils.principal();
    return principal != null ? principal.name() : null;
  }
}

/** Admin stats backing the overview/DLQ endpoints (NATS I/O outside transactions). */
@Service
final class MsgCenterAdminStats {

  private final JdbcTemplate jdbc;
  private final NatsSupport nats;

  MsgCenterAdminStats(JdbcTemplate jdbc, NatsSupport nats) {
    this.jdbc = jdbc;
    this.nats = nats;
  }

  record ModuleStats(String moduleKey, long total, long open, long done) {}

  List<ModuleStats> perModule() {
    return jdbc.query(
        """
        SELECT module_key,
               count(*) AS total,
               count(*) FILTER (WHERE msg_type = 'task' AND status <> 'done') AS open,
               count(*) FILTER (WHERE msg_type = 'task' AND status = 'done') AS done
        FROM mc_messages
        GROUP BY module_key
        ORDER BY module_key
        """,
        (rs, i) ->
            new ModuleStats(
                rs.getString("module_key"),
                rs.getLong("total"),
                rs.getLong("open"),
                rs.getLong("done")));
  }

  /** Read-only peek at the DLQ (AckPolicy.None — nothing consumed). */
  List<String> dlqPeek(int max) {
    if (nats == null || !nats.enabled() || nats.jetStream() == null) {
      return List.of();
    }
    try {
      ConsumerConfiguration config =
          ConsumerConfiguration.builder()
              .filterSubjects("portal.dlq.>")
              .ackPolicy(AckPolicy.None)
              .deliverPolicy(DeliverPolicy.LastPerSubject)
              .build();
      PullSubscribeOptions options =
          PullSubscribeOptions.builder().stream(NatsConnectionConfig.STREAM)
              .configuration(config)
              .bind(false)
              .build();
      io.nats.client.JetStreamSubscription sub = nats.jetStream().subscribe(null, options);
      List<String> out = new java.util.ArrayList<>();
      for (Message m : sub.fetch(max, java.time.Duration.ofSeconds(2))) {
        out.add(new String(m.getData(), java.nio.charset.StandardCharsets.UTF_8));
      }
      sub.unsubscribe();
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }
}

/** Admin surface DTOs (camelCase record contract). */
final class AdminActivityDtos {

  private AdminActivityDtos() {}

  record AdminActivityRow(
      long id,
      String actorSub,
      String actorName,
      String action,
      Map<String, Object> detail,
      String createdAt) {}

  static AdminActivityDtos.AdminActivityRow row(
      Long id,
      String actorSub,
      String actorName,
      String action,
      Map<String, Object> detail,
      String createdAt) {
    return new AdminActivityDtos.AdminActivityRow(
        id, actorSub, actorName, action, detail, createdAt);
  }

  static List<AdminActivityDtos.AdminActivityRow> from(Iterable<McTaskActivityEntity> rows) {
    List<AdminActivityDtos.AdminActivityRow> out = new java.util.ArrayList<>();
    rows.forEach(
        r ->
            out.add(
                row(
                    r.getId(),
                    r.getActorSub(),
                    r.getActorName(),
                    r.getAction(),
                    r.getDetailJson() == null ? Map.of() : r.getDetailJson(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toString())));
    return out;
  }
}
