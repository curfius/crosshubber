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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.crosshubber.portal.auth.KeycloakService;
import com.crosshubber.portal.security.PortalSessionFilter;
import com.crosshubber.portal.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Security filter chain.
 *
 * <p>Permits public endpoints (healthz, i18n public reads, webhooks, login) and requires
 * authentication otherwise. HMAC cookie is validated by {@link PortalSessionFilter} before Spring
 * Security; admin endpoints are guarded via method security
 * ({@code @PreAuthorize("hasRole('...')")}).
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
      KeycloakService keycloak,
      ObjectMapper objectMapper) {
    return new PortalSessionFilter(props, sessionService, keycloak, objectMapper);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, PortalSessionFilter portalSessionFilter) throws Exception {
    log.info("[auth] configuring SecurityFilterChain");
    http.csrf(csrf -> csrf.disable())
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
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/error")
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
        .addFilterBefore(portalSessionFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          log.debug("[auth] unauthorized: {}", req.getRequestURI());
                          res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"error\":\"unauthorized\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          log.warn("[auth] forbidden: {}", req.getRequestURI());
                          res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"error\":\"forbidden\"}");
                        }));
    return http.build();
  }
}
