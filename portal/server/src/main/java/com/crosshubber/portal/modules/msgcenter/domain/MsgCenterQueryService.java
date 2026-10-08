package com.crosshubber.portal.modules.msgcenter.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;

/**
 * Read model for the inbox: the audience predicate {@code users∋me OR roles∩myRoles OR
 * groups∩myGroups OR allUsers OR participation} is composed here (plan §6 domain slice). SQL is
 * hand-assembled because the pivot is the GIN index over {@code audience_json} with per-user array
 * parameters.
 *
 * <p>Array literals for roles/groups are embedded into the SQL text — both vocabularies are
 * regex-filtered first ({@code [A-Za-z0-9_-]+}): realm role names are portal-managed kebab-case and
 * group keys are KEY_RE-validated at creation. Everything else binds as a plain parameter.
 */
@Service
public class MsgCenterQueryService {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;
  private static final Pattern ARRAY_ELEMENT_SAFE = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  /** Inbox row — camelCase by DTO contract (see AgentToolCallDto precedent). */
  public record InboxItemDto(
      long id,
      String eventId,
      String msgType,
      String moduleKey,
      String senderName,
      String senderColor,
      Map<String, Object> title,
      Map<String, Object> body,
      String severity,
      String threadId,
      Map<String, Object> link,
      Map<String, Object> task,
      String status,
      String claimedBySub,
      String claimedByName,
      Instant claimedAt,
      Instant occurredAt,
      boolean read,
      boolean respondedByMe,
      boolean draftedByMe) {}

  private final JdbcTemplate jdbc;
  private final JsonUtils jsonUtils;

  public MsgCenterQueryService(JdbcTemplate jdbc, JsonUtils jsonUtils) {
    this.jdbc = jdbc;
    this.jsonUtils = jsonUtils;
  }

  /**
   * Cursor-paged inbox for one user. {@code type} filters notification/message/task; {@code status}
   * filters task rows (open/claimed/done). Cursor = {@code (occurredAt, id)} descending.
   */
  @Transactional(readOnly = true)
  public List<InboxItemDto> listOwn(
      String userSub,
      List<String> myRoles,
      String type,
      String status,
      String cursorAt,
      Long cursorId,
      Integer limit) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT m.id, m.event_id, m.msg_type, m.module_key, m.occurred_at, m.sender_name,
                   m.sender_color, m.severity, m.thread_id, m.link_json, m.task_json, m.status,
                   m.claimed_by_sub, m.claimed_by_name, m.claimed_at, m.title_json, m.body_json,
                   (rd.user_sub IS NOT NULL) AS read,
                   (r.user_sub IS NOT NULL) AS responded, (d.user_sub IS NOT NULL) AS drafted
            FROM mc_messages m
            LEFT JOIN mc_message_reads rd ON rd.message_id = m.id AND rd.user_sub = ?
            LEFT JOIN mc_task_responses r ON r.message_id = m.id AND r.user_sub = ?
            LEFT JOIN mc_task_drafts d ON d.message_id = m.id AND d.user_sub = ?
            WHERE (""");
    params.add(userSub);
    params.add(userSub);
    params.add(userSub);
    appendAudiencePredicate(sql, params, userSub, myRoles, groupKeys(userSub));

    if (type != null && !type.isBlank()) {
      sql.append("\n AND m.msg_type = ?");
      params.add(type);
    }
    if (status != null && !status.isBlank()) {
      sql.append("\n AND m.status = ?");
      params.add(status);
    }
    if (cursorAt != null && !cursorAt.isBlank() && cursorId != null) {
      sql.append("\n AND (m.occurred_at, m.id) < (?::timestamptz, ?)");
      params.add(Instant.parse(cursorAt).toString());
      params.add(cursorId);
    }
    int pageSize = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
    sql.append("\n ) ORDER BY m.occurred_at DESC, m.id DESC LIMIT ?");
    params.add(pageSize);

    return jdbc.query(sql.toString(), this::mapRow, params.toArray());
  }

  /** Total unread rows for the badge: audience-matched without a read marker. */
  @Transactional(readOnly = true)
  public long unread(String userSub, List<String> myRoles) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM mc_messages m WHERE (");
    List<Object> params = new ArrayList<>();
    appendAudiencePredicate(sql, params, userSub, myRoles, groupKeys(userSub));
    sql.append(
        "\n ) AND NOT EXISTS (SELECT 1 FROM mc_message_reads rd WHERE rd.message_id = m.id"
            + " AND rd.user_sub = ?)");
    params.add(userSub);
    Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
    return count == null ? 0 : count;
  }

  /** Marks a visible message read (idempotent insert-or-ignore). 404 when not visible. */
  @Transactional
  public void markRead(String userSub, List<String> myRoles, long messageId) {
    if (!visible(userSub, myRoles, messageId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "message not visible: " + messageId);
    }
    jdbc.update(
        "INSERT INTO mc_message_reads (message_id, user_sub, read_at) VALUES (?, ?, now())"
            + " ON CONFLICT DO NOTHING",
        messageId,
        userSub);
  }

  /** Audience-gated single-item fetch (agent msgcenter_get) — 404 when not visible. */
  @Transactional(readOnly = true)
  public InboxItemDto getOwn(String userSub, List<String> myRoles, long messageId) {
    if (!visible(userSub, myRoles, messageId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "message not visible: " + messageId);
    }
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT m.id, m.event_id, m.msg_type, m.module_key, m.occurred_at, m.sender_name,
                   m.sender_color, m.severity, m.thread_id, m.link_json, m.task_json, m.status,
                   m.claimed_by_sub, m.claimed_by_name, m.claimed_at, m.title_json, m.body_json,
                   (rd.user_sub IS NOT NULL) AS read,
                   (r.user_sub IS NOT NULL) AS responded, (d.user_sub IS NOT NULL) AS drafted
            FROM mc_messages m
            LEFT JOIN mc_message_reads rd ON rd.message_id = m.id AND rd.user_sub = ?
            LEFT JOIN mc_task_responses r ON r.message_id = m.id AND r.user_sub = ?
            LEFT JOIN mc_task_drafts d ON d.message_id = m.id AND d.user_sub = ?
            WHERE m.id = ? AND (""");
    List<Object> params = new ArrayList<>();
    params.add(userSub);
    params.add(userSub);
    params.add(userSub);
    params.add(messageId);
    appendAudiencePredicate(sql, params, userSub, myRoles, groupKeys(userSub));
    List<InboxItemDto> rows = jdbc.query(sql.toString() + ")", this::mapRow, params.toArray());
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Audience predicate check for one message (claim/respond gating for agent tools). */
  @Transactional(readOnly = true)
  public boolean visible(String userSub, List<String> myRoles, long messageId) {
    StringBuilder sql = new StringBuilder("SELECT m.id FROM mc_messages m WHERE m.id = ? AND (");
    List<Object> params = new ArrayList<>();
    params.add(messageId);
    appendAudiencePredicate(sql, params, userSub, myRoles, groupKeys(userSub));
    return !jdbc.query(sql.toString() + ")", (rs, i) -> rs.getLong(1), params.toArray()).isEmpty();
  }

  /** Appends the unclosed audience predicate — CALLERS must append the closing paren. */
  private void appendAudiencePredicate(
      StringBuilder sql,
      List<Object> params,
      String userSub,
      List<String> myRoles,
      List<String> myGroups) {
    // jsonb_exists_any is the function form of the ?| operator — the bare `?` character in the
    // operator spelling is treated as a bind placeholder by the PostgreSQL JDBC driver, so it
    // must not appear in this SQL.
    sql.append(
        """
          COALESCE(jsonb_exists_any(m.audience_json->'users', %s), false)
          OR COALESCE(jsonb_exists_any(m.audience_json->'roles', %s), false)
          OR COALESCE(jsonb_exists_any(m.audience_json->'groups', %s), false)
          OR COALESCE(m.audience_json->>'allUsers', 'false') = 'true'
          OR m.claimed_by_sub = ?
          OR EXISTS (SELECT 1 FROM mc_task_responses r WHERE r.message_id = m.id AND r.user_sub = ?)
          OR EXISTS (SELECT 1 FROM mc_task_drafts d WHERE d.message_id = m.id AND d.user_sub = ?)
        """
            .formatted(
                arrayLiteral(List.of(userSub)), arrayLiteral(myRoles), arrayLiteral(myGroups)));
    // three equality params after the three inline array literals
    params.add(userSub);
    params.add(userSub);
    params.add(userSub);
  }

  /** {@code ARRAY['a','b']::text[]} from regex-safe values; empty array literal when none. */
  static String arrayLiteral(List<String> values) {
    List<String> safe =
        values == null
            ? List.of()
            : values.stream().filter(ARRAY_ELEMENT_SAFE.asMatchPredicate()).toList();
    if (safe.isEmpty()) {
      return "ARRAY[]::text[]";
    }
    return safe.stream()
        .map(v -> "'" + v + "'")
        .collect(java.util.stream.Collectors.joining(",", "ARRAY[", "]::text[]"));
  }

  private List<String> groupKeys(String userSub) {
    return jdbc.query(
        "SELECT g.key FROM mc_groups g JOIN mc_group_members m ON m.group_id = g.id"
            + " WHERE m.user_sub = ? AND g.retired_at IS NULL",
        (rs, i) -> rs.getString(1),
        userSub);
  }

  private InboxItemDto mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new InboxItemDto(
        rs.getLong("id"),
        rs.getString("event_id"),
        rs.getString("msg_type"),
        rs.getString("module_key"),
        rs.getString("sender_name"),
        rs.getString("sender_color"),
        jsonMap(rs, "title_json"),
        jsonMap(rs, "body_json"),
        rs.getString("severity"),
        rs.getString("thread_id"),
        jsonMap(rs, "link_json"),
        jsonMap(rs, "task_json"),
        rs.getString("status"),
        rs.getString("claimed_by_sub"),
        rs.getString("claimed_by_name"),
        toInstant(rs.getTimestamp("claimed_at")),
        toInstant(rs.getTimestamp("occurred_at")),
        rs.getBoolean("read"),
        rs.getBoolean("responded"),
        rs.getBoolean("drafted"));
  }

  private Map<String, Object> jsonMap(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    Object value = rs.getObject(column);
    if (value == null) {
      return null;
    }
    // PG JDBC returns PGobject; toString() yields the raw JSON in both driver variants
    Map<String, Object> map = jsonUtils.parseMapOrNull(value.toString());
    return map == null ? new java.util.LinkedHashMap<>() : map;
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
