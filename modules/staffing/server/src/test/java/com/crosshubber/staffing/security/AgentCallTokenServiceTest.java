package com.crosshubber.staffing.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.staffing.config.StaffingProperties;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Portal agent-call token contract: round-trip, tamper rejection, expiry (P5). */
class AgentCallTokenServiceTest {

  private static final String SECRET = "test-shared-secret";

  private AgentCallTokenService service;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    StaffingProperties props = new StaffingProperties();
    props.setAgentSharedSecret(SECRET);
    mapper = JsonMapper.builder().build();
    service = new AgentCallTokenService(props, mapper);
  }

  private String issue(String sub, String name, List<String> roles, long expEpochSecond) {
    try {
      String json =
          mapper.writeValueAsString(
              new AgentCallTokenService.AgentCallClaims(
                  "portal", sub, name, roles, expEpochSecond));
      String payload =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(json.getBytes(StandardCharsets.UTF_8));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
      return payload + "." + signature;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void verifiesPortalIssuedToken() {
    String token =
        issue("u1", "Dev Admin", List.of("staffing-user"), Instant.now().getEpochSecond() + 300);

    var claims = service.verify(token);

    assertThat(claims).isNotNull();
    assertThat(claims.sub()).isEqualTo("u1");
    assertThat(claims.roles()).containsExactly("staffing-user");
  }

  @Test
  void rejectsExpiredToken() {
    String token = issue("u1", "Dev Admin", List.of(), Instant.now().getEpochSecond() - 10);
    assertThat(service.verify(token)).isNull();
  }

  @Test
  void rejectsTamperedSignature() {
    String token = issue("u1", "Dev Admin", List.of(), Instant.now().getEpochSecond() + 300);
    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    assertThat(service.verify(tampered)).isNull();
  }

  @Test
  void rejectsWrongIssuerAndMissingSubject() {
    long exp = Instant.now().getEpochSecond() + 300;
    String wrongIss =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    new AgentCallTokenService.AgentCallClaims("other", "u1", "n", List.of(), exp)));
    assertThat(service.verify(wrongIss)).isNull();

    String noSub =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    new AgentCallTokenService.AgentCallClaims(
                        "portal", null, "n", List.of(), exp)));
    assertThat(service.verify(noSub)).isNull();
  }

  @Test
  void rejectsGarbage() {
    assertThat(service.verify(null)).isNull();
    assertThat(service.verify("")).isNull();
    assertThat(service.verify("no-signature")).isNull();
  }
}
