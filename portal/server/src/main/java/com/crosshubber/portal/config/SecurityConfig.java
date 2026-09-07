package com.crosshubber.portal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.crosshubber.portal.auth.KeycloakOidcUserService;
import com.crosshubber.portal.auth.KeycloakService;
import com.crosshubber.portal.auth.OidcSuccessHandler;
import com.crosshubber.portal.security.PortalSessionFilter;
import com.crosshubber.portal.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Security filter chain.
 *
 * <p>Permits public endpoints (healthz, i18n public reads, webhooks, login/OIDC dance) and requires
 * authentication otherwise. The {@code portalSession} HMAC cookie is validated by {@link
 * PortalSessionFilter} before Spring Security; admin endpoints are guarded via method security
 * ({@code @PreAuthorize("hasRole('...')")}).
 *
 * <p>Login is the standard OIDC authorization-code redirect (oauth2-client): {@code
 * /api/login/start} 302s to {@code /oauth2/authorization/portal}, the callback lands on {@code
 * /login/oauth2/code/portal}, and {@link OidcSuccessHandler} mints the session cookie. The explicit
 * 401 JSON entry point below overrides oauth2Login's redirect entry point so unauthenticated API
 * calls keep their contract.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  @Bean
  public PortalSessionFilter portalSessionFilter(
      PortalProperties props,
      SessionService sessionService,
      KeycloakService keycloakService,
      ObjectMapper objectMapper) {
    return new PortalSessionFilter(props, sessionService, keycloakService, objectMapper);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      PortalSessionFilter portalSessionFilter,
      OidcSuccessHandler oidcSuccessHandler,
      OAuth2AuthorizationRequestResolver authorizationRequestResolver,
      ObjectMapper objectMapper)
      throws Exception {
    log.info("[auth] configuring SecurityFilterChain with OIDC redirect login");
    http.csrf(csrf -> csrf.disable())
        // Disable the default LogoutFilter — GET /logout is handled by AuthController
        // (revoke session, clear cookie, Keycloak end-session vs /login for anonymous).
        .logout(logout -> logout.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/healthz")
                    .permitAll()
                    .requestMatchers("/api/i18n/config", "/api/i18n/labels/**")
                    .permitAll()
                    .requestMatchers("/api/ai-hub/webhooks/**")
                    .permitAll()
                    .requestMatchers("/api/login/**", "/logout")
                    .permitAll()
                    // OIDC authorization-code dance (P7.3)
                    .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    // SPA static assets and routes — public (auth handled client-side)
                    .requestMatchers(
                        "/",
                        "/login",
                        "/w/**",
                        "/index.html",
                        "/*.js",
                        "/*.css",
                        "/*.ico",
                        "/*.map",
                        "/*.json",
                        "/assets/**",
                        "/public/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint.authorizationRequestResolver(authorizationRequestResolver))
                    .userInfoEndpoint(
                        userInfo ->
                            userInfo.oidcUserService(new KeycloakOidcUserService(objectMapper)))
                    .successHandler(oidcSuccessHandler)
                    .failureHandler(
                        (req, res, ex) -> {
                          log.warn("[auth] OIDC login failed: {}", ex.getMessage());
                          res.sendRedirect("/login?error=oidc");
                        }))
        .addFilterBefore(portalSessionFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          if (res.isCommitted()) return;
                          log.debug("[auth] unauthorized: {}", req.getRequestURI());
                          res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"error\":\"unauthorized\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          if (res.isCommitted()) return;
                          log.warn("[auth] forbidden: {}", req.getRequestURI());
                          res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"error\":\"forbidden\"}");
                        }));
    return http.build();
  }
}
