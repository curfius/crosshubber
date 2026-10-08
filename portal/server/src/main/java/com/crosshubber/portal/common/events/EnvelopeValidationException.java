package com.crosshubber.portal.common.events;

/** Envelope contract violation — HTTP publish surfaces this as 422; ingest routes it to the DLQ. */
public class EnvelopeValidationException extends RuntimeException {

  public EnvelopeValidationException(String message) {
    super(message);
  }
}
