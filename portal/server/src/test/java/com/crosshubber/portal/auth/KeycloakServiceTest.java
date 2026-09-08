package com.crosshubber.portal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link KeycloakService#logoutUrl} parameter encoding. */
class KeycloakServiceTest {

  private KeycloakService service() {
    PortalProperties props = new PortalProperties();
    props.setIssuer("https://kc.example.com/realms/dev");
    props.setClientSecret("x");
    props.setSessionSecret("x");
    props.setPublicBaseUrl("https://portal.example.com");
    return new KeycloakService(props, JsonMapper.builder().build(), RestClient.builder());
  }

  @Test
  void logoutUrlEncodesQueryInjection() {
    String url =
        service().logoutUrl("a.b_c-d", "https://portal.example.com/login?next=/&evil=injected");
    assertTrue(url.startsWith("https://kc.example.com/realms/dev/protocol/openid-connect/logout?"));
    // The injection attempt must be percent-encoded, not left as a raw parameter separator
    assertFalse(url.contains("&evil=injected"));
    assertTrue(url.contains("post_logout_redirect_uri=https%3A%2F%2Fportal.example.com%2Flogin"));
    assertTrue(url.contains("id_token_hint=a.b_c-d"));
  }

  @Test
  void logoutUrlOmitsBlankIdTokenHint() {
    String url = service().logoutUrl(" ", "https://portal.example.com/login");
    assertFalse(url.contains("id_token_hint"));
  }
}
