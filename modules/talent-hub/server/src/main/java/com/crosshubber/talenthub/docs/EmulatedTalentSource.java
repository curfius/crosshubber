package com.crosshubber.talenthub.docs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Emulated talent source (deterministic fixtures): three candidates with fake CV refs resolvable by
 * {@code FakeDocumentSource}. Claimed refs vanish from the inbox until restart (dev-grade in-memory
 * state).
 */
@Component
@ConditionalOnProperty(
    name = "talenthub.docsource.kind",
    havingValue = "fake",
    matchIfMissing = true)
public class EmulatedTalentSource implements TalentSourceConnector {

  private static final List<InboxItem> FIXTURES =
      List.of(
          new InboxItem(
              "src-cv-dan",
              "Dan Silva",
              "Backend platform engineer, Java/Rust",
              "cv-ana",
              "Referred by a partner agency; corpus CV cv-ana"),
          new InboxItem(
              "src-cv-martina",
              "Martina Beck",
              "QA automation, Cypress/Playwright",
              "cv-bruno",
              "Applied via the careers page; corpus CV cv-bruno"),
          new InboxItem(
              "src-cv-hugo",
              "Hugo Alves",
              "Data engineer, Python/dbt",
              "cv-carla",
              "Sourced from a meetup; corpus CV cv-carla"));

  private final Map<String, InboxItem> claimed = new ConcurrentHashMap<>();

  @Override
  public List<InboxItem> inbox() {
    return FIXTURES.stream().filter(item -> !claimed.containsKey(item.ref())).toList();
  }

  @Override
  public ClaimResult claim(String ref) {
    InboxItem item =
        FIXTURES.stream()
            .filter(f -> f.ref().equals(ref))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown intake ref: " + ref));
    if (claimed.putIfAbsent(ref, item) != null) {
      throw new IllegalArgumentException("already claimed: " + ref);
    }
    return new ClaimResult(item.ref(), item.documentRef(), item.name());
  }
}
