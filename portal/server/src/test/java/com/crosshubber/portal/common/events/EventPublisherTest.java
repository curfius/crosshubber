package com.crosshubber.portal.common.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.NatsConnectionConfig.NatsSupport;

class EventPublisherTest {

  private final EventPublisher disabled =
      new EventPublisher(new NatsSupport(false, null, null, null));

  @Test
  void portalSubjectsOnly() {
    assertTrue(EventPublisher.isPortalSubject("portal.msg.solutions.timesheet-closed"));
    assertTrue(EventPublisher.isPortalSubject("portal.task.sample-sender.hours.submitted"));
    assertTrue(EventPublisher.isPortalSubject("portal.taskresponse.sample-sender.hours.submitted"));
    assertFalse(EventPublisher.isPortalSubject("portal.other.solutions.x"));
    assertFalse(EventPublisher.isPortalSubject(null));
  }

  @Test
  void rejectsNonPortalSubjectEvenWhenDisabled() {
    assertThrows(EnvelopeValidationException.class, () -> disabled.publish("other.subject", "{}"));
  }

  @Test
  void dropsQuietlyWhenDisabled() {
    EventPublisher.PublishResult result =
        assertDoesNotThrow(() -> disabled.publish("portal.msg.solutions.x", "{}"));
    assertFalse(result.published());
    assertEquals(-1, result.streamSeq());
  }

  @Test
  void enabledFlagMirrorsSupport() {
    assertFalse(disabled.isEnabled());
  }
}
