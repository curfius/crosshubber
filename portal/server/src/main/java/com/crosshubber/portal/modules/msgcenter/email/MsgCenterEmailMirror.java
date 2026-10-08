package com.crosshubber.portal.modules.msgcenter.email;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.msgcenter.groups.McGroupMemberRepository;
import com.crosshubber.portal.modules.msgcenter.groups.McGroupRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsService;

/**
 * Immediate-mirror delivery (Phase 7, plan §10): event-driven hook fired by the ingest loop AFTER
 * the message row commits. Resolves the audience to per-recipient email preferences (group members
 * by {@code mc_group_members.email_flag}; pinned/roles/allUsers by the user's global fallback
 * switch), renders plain text from the item's i18n maps with the recipient's language, and sends on
 * virtual threads. Arrival-only: claims/releases/responses never email.
 *
 * <p>Recipient addresses are self-supplied (user_settings scope {@code msgcenter} key {@code
 * email}) — no Keycloak I/O in the mirror path (mirrors the ingest-path rule).
 */
@Service
public class MsgCenterEmailMirror {

  private static final Logger log = LoggerFactory.getLogger(MsgCenterEmailMirror.class);
  private static final String EMAIL_SCOPE = "msgcenter";

  private final SmtpConfigService smtpConfigService;
  private final MsgCenterEmailSender sender;
  private final McGroupRepository groupRepo;
  private final McGroupMemberRepository memberRepo;
  private final UserSettingsService userSettingsService;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public MsgCenterEmailMirror(
      SmtpConfigService smtpConfigService,
      MsgCenterEmailSender sender,
      McGroupRepository groupRepo,
      McGroupMemberRepository memberRepo,
      UserSettingsService userSettingsService) {
    this.smtpConfigService = smtpConfigService;
    this.sender = sender;
    this.groupRepo = groupRepo;
    this.memberRepo = memberRepo;
    this.userSettingsService = userSettingsService;
  }

  /** Ingest-loop hook — returns immediately; sending happens on virtual threads. */
  public void onArrival(McMessageEntity message) {
    SmtpConfig config = smtpConfigService.live();
    if (config == null || !config.enabled()) {
      return; // channel off — no-op
    }
    Map<String, Object> audience = message.getAudienceJson();
    List<Recipient> recipients = resolveRecipients(message, audience);
    if (recipients.isEmpty()) {
      return;
    }
    executor.submit(
        () -> {
          for (Recipient recipient : recipients) {
            String subject = pick(message.getTitleJson(), recipient.language()) + " — Crosshubber";
            String body = renderBody(message, recipient.language());
            boolean sent = sender.mirror(message, config, recipient.address(), subject, body);
            if (!sent) {
              log.debug(
                  "[msgcenter-email] mirror to {} skipped (cap/offline)", recipient.address());
            }
          }
        });
  }

  record Recipient(String sub, String address, String language) {}

  /**
   * Recipients = (group-addressed: members with email_flag) ∪ (pinned/roles/allUsers: users with
   * the global fallback switch). Deduplicated per address.
   */
  List<Recipient> resolveRecipients(McMessageEntity message, Map<String, Object> audience) {
    Map<String, Recipient> out = new java.util.LinkedHashMap<>();
    Object groupList = audience.get("groups");
    if (groupList instanceof List<?> groups && !groups.isEmpty()) {
      for (Object key : groups) {
        groupRepo
            .findByKey(String.valueOf(key))
            .filter(g -> g.isLive())
            .ifPresent(
                group ->
                    memberRepo.findByGroupId(group.getId()).stream()
                        .filter(m -> m.isEmailFlag())
                        .forEach(m -> addIfSubscribed(out, m.getUserSub())));
      }
    }
    boolean generalChannel =
        (audience.get("users") instanceof List<?> users && !users.isEmpty())
            || (audience.get("roles") instanceof List<?> roles && !roles.isEmpty())
            || Boolean.TRUE.equals(audience.get("allUsers"));
    if (generalChannel) {
      // fallback switch governs non-group channels; enumerate subscribers lazily: candidates
      // come from pinned users + (roles/allUsers) = every user with the switch — read lazily
      List<String> pinned =
          audience.get("users") instanceof List<?> users
              ? users.stream().map(String::valueOf).toList()
              : List.of();
      for (String sub : pinned) {
        addIfSubscribed(out, sub);
      }
      if (audience.get("roles") instanceof List<?> roles && !roles.isEmpty()
          || Boolean.TRUE.equals(audience.get("allUsers"))) {
        // subscriber roster: every user holding the fallback switch; queried per arrival
        for (String sub : fallbackSubscribers()) {
          addIfSubscribed(out, sub);
        }
      }
    }
    return List.copyOf(out.values());
  }

  /**
   * Roster query: users whose {@code msgcenter} scope holds {@code emailFallback=true} AND an
   * address. Small installs: full scan of the scope; bounded by installed-user count.
   */
  private List<String> fallbackSubscribers() {
    return userSettingsService.getAllByScope(EMAIL_SCOPE).entrySet().stream()
        .filter(
            e -> {
              Object fallback = e.getValue().get("emailFallback");
              return Boolean.TRUE.equals(fallback);
            })
        .map(e -> e.getKey())
        .toList();
  }

  /** Only users who subscribed (address set AND switch on, or group flag) receive mail. */
  private void addIfSubscribed(Map<String, Recipient> out, String sub) {
    if (sub == null || out.containsKey(sub)) {
      return;
    }
    Map<String, Object> prefs = userSettingsService.get(sub, EMAIL_SCOPE);
    String address = prefs.get("email") instanceof String s && s.contains("@") ? s : null;
    if (address == null) {
      return; // no address = no mirror (documented default)
    }
    String language =
        userSettingsService.get(sub, "general").get("language") instanceof String lang
                && !lang.isBlank()
            ? lang
            : "en";
    out.put(sub, new Recipient(sub, address, language));
  }

  /** Plain-text rendering: intro + titled sections; line breaks honored. */
  String renderBody(McMessageEntity message, String language) {
    StringBuilder out = new StringBuilder();
    String intro = pick(message.getBodyJson(), language);
    if (!intro.isBlank()) {
      out.append(intro).append("\n");
    }
    Object sections = message.getBodyJson().get("sections");
    if (sections instanceof List<?> list) {
      for (Object section : list) {
        if (section instanceof Map<?, ?> s) {
          String title = pick(cast(s.get("title")), language);
          String text = pick(cast(s.get("text")), language);
          if (!title.isBlank()) {
            out.append("\n").append(title.toUpperCase()).append("\n");
          }
          if (!text.isBlank()) {
            out.append(text).append("\n");
          }
        }
      }
    }
    out.append("\n— Crosshubber Portal (message center mirror)");
    return out.toString();
  }

  /** Best-value pick: recipient language → en → first present. */
  static String pick(Map<String, Object> map, String language) {
    if (map == null) {
      return "";
    }
    Object preferred = map.get(language);
    if (preferred instanceof String s && !s.isBlank()) {
      return s;
    }
    Object en = map.get("en");
    if (en instanceof String s && !s.isBlank()) {
      return s;
    }
    return map.values().stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .findFirst()
        .orElse("");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }
}
