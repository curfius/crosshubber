package com.crosshubber.portal.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * OIDC client registration for Keycloak — mirrors {@code keycloak-identity-provider.ts} discovery
 * semantics: the issuer/endpoints are the browser-facing (public) Keycloak URL so the authorization
 * redirect and {@code iss} claim match what the metadata advertises; server-side calls to the same
 * URLs work in compose via {@code extra_hosts: localhost:host-gateway}.
 *
 * <p>Registration is built in code (not YAML) because the public/internal issuer split comes from
 * {@link PortalProperties}. PKCE (S256) is enabled explicitly — Spring only auto-enables it for
 * public clients, and {@code portal} is confidential.
 */
@Configuration
public class OAuth2ClientConfig {

  private static final Logger log = LoggerFactory.getLogger(OAuth2ClientConfig.class);

  /**
   * Scopes mirror Node {@code begin()} exactly ({@code openid profile email}). Keycloak issues a
   * session refresh token for the code grant without needing {@code offline_access} — requesting
   * that scope would hard-fail the token exchange unless every user holds the offline_access realm
   * role (verified against the compose realm).
   */
  private static final String[] SCOPES = {"openid", "profile", "email"};

  @Bean
  public ClientRegistrationRepository clientRegistrationRepository(PortalProperties props) {
    // Browser-facing issuer (KC_HOSTNAME) for the authorization endpoint and the expected iss
    // claim; internal issuer for server-side calls — the public host is not reachable from
    // inside the compose network (localhost does not resolve to the host gateway).
    String publicIssuer = props.getEffectiveIssuer();
    String internalIssuer = props.getIssuer();
    log.info(
        "[auth] OIDC client registration \"portal\" publicIssuer={} internalIssuer={}",
        publicIssuer,
        internalIssuer);
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("portal")
            .clientId("portal")
            .clientSecret(props.getClientSecret())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope(SCOPES)
            .authorizationUri(publicIssuer + "/protocol/openid-connect/auth")
            .tokenUri(internalIssuer + "/protocol/openid-connect/token")
            .userInfoUri(internalIssuer + "/protocol/openid-connect/userinfo")
            .jwkSetUri(internalIssuer + "/protocol/openid-connect/certs")
            // Expected iss claim — Keycloak advertises the public hostname (KC_HOSTNAME)
            .issuerUri(publicIssuer)
            .userNameAttributeName("preferred_username")
            .clientName("portal")
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  /**
   * Authorization request resolver with PKCE enabled for the confidential client: stores the {@code
   * code_verifier} as a request attribute (replayed on the token exchange) and sends {@code
   * code_challenge}/{@code code_challenge_method=S256} to the provider.
   */
  @Bean
  public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      ClientRegistrationRepository clientRegistrationRepository) {
    DefaultOAuth2AuthorizationRequestResolver resolver =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(
        builder -> {
          String codeVerifier = createCodeVerifier();
          builder.attributes(attrs -> attrs.put(PkceParameterNames.CODE_VERIFIER, codeVerifier));
          builder.additionalParameters(
              params -> {
                params.put(PkceParameterNames.CODE_CHALLENGE, createCodeChallenge(codeVerifier));
                params.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
              });
        });
    return resolver;
  }

  /** 32 random bytes, base64url — mirrors {@code randomPKCECodeVerifier()}. */
  private static String createCodeVerifier() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** BASE64URL(SHA256(verifier)) — mirrors {@code calculatePKCECodeChallenge()} (S256). */
  private static String createCodeChallenge(String codeVerifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception e) {
      throw new IllegalStateException("PKCE challenge derivation failed", e);
    }
  }
}
