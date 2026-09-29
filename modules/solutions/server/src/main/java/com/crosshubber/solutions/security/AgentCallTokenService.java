package com.crosshubber.solutions.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.crosshubber.solutions.config.SolutionsProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates portal-minted agent-call tokens ({@code X-Portal-Agent} header) — the module side of
 * the contract implemented by the portal's {@code AgentCallAuthorizer}: {@code
 * base64url(payloadJson) + "." + base64url(HmacSHA256(payload))} with payload {@code {iss:"portal",
 * sub, name, roles, exp}} and a 5-minute expiry, keyed with the shared secret (compose env).
 *
 * <p>Fail-closed: any malformed signature, wrong issuer, expired token or missing subject returns
 * null and the caller is treated as anonymous.
 */
@Service
public class AgentCallTokenService {

  private static final String ALGORITHM = "HmacSHA256";

  /** Token claims (mirror of the portal's {@code AgentCallClaims}). */
  public record AgentCallClaims(
      String iss, String sub, String name, List<String> roles, long exp) {}

  private final String sharedSecret;
  private final ObjectMapper objectMapper;

  public AgentCallTokenService(SolutionsProperties props, ObjectMapper objectMapper) {
    this.sharedSecret = props.getAgentSharedSecret();
    this.objectMapper = objectMapper;
  }

  /**
   * Verifies signature, issuer, expiry and subject.
   *
   * @return the claims, or null when the token is invalid/expired
   */
  public AgentCallClaims verify(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    int sep = token.lastIndexOf('.');
    if (sep <= 0) {
      return null;
    }
    String payload = token.substring(0, sep);
    String signature = token.substring(sep + 1);
    byte[] expected = sign(payload);
    byte[] provided;
    try {
      provided = Base64.getUrlDecoder().decode(signature);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (!MessageDigest.isEqual(expected, provided)) {
      return null;
    }
    try {
      String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
      JsonNode node = objectMapper.readTree(json);
      if (node == null || !node.isObject()) {
        return null;
      }
      List<String> roles = new ArrayList<>();
      if (node.path("roles").isArray()) {
        node.path("roles").forEach(r -> roles.add(r.asString()));
      }
      String iss = node.path("iss").asString(null);
      String sub = node.path("sub").asString(null);
      long exp = node.path("exp").asLong(0);
      if (!"portal".equals(iss) || sub == null || sub.isBlank()) {
        return null;
      }
      if (exp < Instant.now().getEpochSecond()) {
        return null;
      }
      String name = node.path("name").asString(null);
      return new AgentCallClaims(iss, sub, name, List.copyOf(roles), exp);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private byte[] sign(String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("agent call token signing failed", e);
    }
  }
}
