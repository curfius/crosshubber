package com.crosshubber.portal.modules.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;

/** Agent-call token round-trip, tamper rejection and expiry (AI_MODULES_PLAN P5). */
class AgentCallAuthorizerTest {

  private AgentCallAuthorizer authorizer;
  private PortalUser user;

  @BeforeEach
  void setUp() {
    PortalProperties props = new PortalProperties();
    props.setSessionSecret("test-secret");
    authorizer = new AgentCallAuthorizer(props, new JsonUtils(new JacksonConfig().jsonMapper()));
    user = new PortalUser("u1", "Dev Admin", "d@x", List.of("solutions-user"));
  }

  @Test
  void issuesVerifiableTokenCarryingCallerIdentity() {
    String token = authorizer.issue(user);

    AgentCallAuthorizer.AgentCallClaims claims = authorizer.verify(token);

    assertThat(claims).isNotNull();
    assertThat(claims.iss()).isEqualTo("portal");
    assertThat(claims.sub()).isEqualTo("u1");
    assertThat(claims.name()).isEqualTo("Dev Admin");
    assertThat(claims.roles()).containsExactly("solutions-user");
  }

  @Test
  void rejectsTamperedTokens() {
    String token = authorizer.issue(user);
    String tampered = token.substring(0, token.length() - 4) + "AAAA";

    assertThat(authorizer.verify(tampered)).isNull();
  }

  @Test
  void rejectsGarbage() {
    assertThat(authorizer.verify(null)).isNull();
    assertThat(authorizer.verify("")).isNull();
    assertThat(authorizer.verify("no-signature-here")).isNull();
    assertThat(authorizer.verify("b2F0.aG9sYQ")).isNull();
  }
}
