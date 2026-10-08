package com.crosshubber.portal.modules.msgcenter.email;

import java.time.Duration;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * SMTP sender for the message-center mirror (Phase 7): builds a {@link Session} from the stored
 * config per send (cheap — no pool to invalidate on config change), plain-text bodies, sent on
 * virtual threads by the caller. Noise guard: in-memory per-recipient cap (~50/24h), then drop with
 * one WARN line.
 */
@Service
public class MsgCenterEmailSender {

  private static final Logger log = LoggerFactory.getLogger(MsgCenterEmailSender.class);

  private static final int DAILY_CAP = 50;
  private final Cache<String, java.util.concurrent.atomic.AtomicLong> sent =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(24)).maximumSize(10_000).build();

  /** Sends one plain-text mail; returns false when skipped (cap) or failed. */
  public boolean send(SmtpConfig config, String to, String subject, String body) {
    if (config == null || !config.enabled()) {
      return false;
    }
    var counter = sent.get(to, k -> new java.util.concurrent.atomic.AtomicLong(0));
    if (counter.incrementAndGet() > DAILY_CAP) {
      log.warn("[msgcenter-email] daily cap reached for {} — dropping mirror mail", to);
      return false;
    }
    try {
      Session session = session(config);
      MimeMessage mail = new MimeMessage(session);
      mail.setFrom(
          new InternetAddress(config.from() == null ? "portal@crosshubber.local" : config.from()));
      mail.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
      mail.setSubject(subject, "UTF-8");
      mail.setText(body, "UTF-8");
      mail.setSentDate(new java.util.Date());
      try (Transport transport = session.getTransport()) {
        if (config.authUser() != null && !config.authUser().isBlank()) {
          transport.connect(
              config.host(),
              config.port(),
              config.authUser(),
              config.secret() == null ? "" : config.secret());
        } else {
          transport.connect();
        }
        transport.sendMessage(mail, mail.getAllRecipients());
      }
      return true;
    } catch (Exception e) {
      log.warn("[msgcenter-email] send to {} failed: {}", to, e.getMessage());
      return false;
    }
  }

  /** Sends bypassing the daily cap (send-test button). */
  public boolean sendTest(SmtpConfig config, String to) {
    var counter = sent.get(to, k -> new java.util.concurrent.atomic.AtomicLong(0));
    boolean ok = sendInternal(config, to);
    counter.decrementAndGet(); // the probe does not count against the cap
    return ok;
  }

  private boolean sendInternal(SmtpConfig config, String to) {
    return send(
        config,
        to,
        "Crosshubber Portal — SMTP test",
        "SMTP configuration works. If you read this in Mailpit, the email mirror is ready.");
  }

  private Session session(SmtpConfig config) {
    Properties props = new Properties();
    props.put("mail.smtp.host", config.host());
    props.put("mail.smtp.port", String.valueOf(config.port()));
    boolean tls =
        config.tls() != null
            && ("starttls".equalsIgnoreCase(config.tls()) || "tls".equalsIgnoreCase(config.tls()));
    props.put("mail.smtp.starttls.enable", String.valueOf(tls));
    props.put("mail.smtp.connectiontimeout", "5000");
    props.put("mail.smtp.timeout", "10000");
    final String user = config.authUser();
    final String secret = config.secret();
    Authenticator authenticator =
        user == null || user.isBlank()
            ? null
            : new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, secret == null ? "" : secret);
              }
            };
    return authenticator == null
        ? Session.getInstance(props)
        : Session.getInstance(props, authenticator);
  }

  /** Mirror entry used by the delivery loop — subject/body rendered by the caller. */
  public boolean mirror(
      McMessageEntity message, SmtpConfig config, String to, String subject, String body) {
    return send(config, to, subject, body);
  }

  /** Visible for tests: counter reset helper (time-based tests). */
  void resetCaps() {
    sent.invalidateAll();
  }

  static long cap() {
    return DAILY_CAP;
  }
}
