package com.crosshubber.portal.modules.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;

/**
 * In-memory store of pending mutating tool calls awaiting user confirmation (AI plan B6).
 *
 * <p>A mutating dispatch first lands here and returns {@code needs_confirmation} to the model; the
 * UI (or a follow-up request carrying {@code toolConfirmation.callId}) then confirms and the
 * dispatcher re-runs the call. Entries expire after 10 minutes and are caller-scoped: only the user
 * who triggered the call can confirm it. Capacity-capped to keep abuse cheap to absorb.
 */
@Service
public class PendingToolCallStore {

  private static final Duration TTL = Duration.ofMinutes(10);
  private static final int MAX_PENDING = 100;

  /** A waiting mutating call. */
  public record PendingCall(
      String callId,
      AgentTool tool,
      PortalUser user,
      String conversationId,
      JsonNode args,
      Instant createdAt) {}

  private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

  /** Stores a pending call; returns its id, or null when the store is full. */
  public String create(AgentTool tool, PortalUser user, String conversationId, JsonNode args) {
    if (pending.size() >= MAX_PENDING) {
      evictExpired();
      if (pending.size() >= MAX_PENDING) {
        return null;
      }
    }
    String callId = "call_" + UUID.randomUUID();
    pending.put(callId, new PendingCall(callId, tool, user, conversationId, args, Instant.now()));
    return callId;
  }

  /**
   * Takes a pending call owned by {@code userId} (removing it from the store). Returns null when
   * missing, expired, or owned by someone else.
   */
  public PendingCall take(String callId, String userId) {
    if (callId == null) {
      return null;
    }
    PendingCall call = pending.remove(callId);
    if (call == null) {
      return null;
    }
    if (call.createdAt().plus(TTL).isBefore(Instant.now())) {
      return null;
    }
    if (!call.user().sub().equals(userId)) {
      // wrong owner — put it back so the legitimate owner can still confirm
      pending.put(callId, call);
      return null;
    }
    return call;
  }

  private void evictExpired() {
    Instant cutoff = Instant.now().minus(TTL);
    pending.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
  }
}
