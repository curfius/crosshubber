package com.crosshubber.staffing.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.crosshubber.staffing.security.AgentAuthenticationFilter;

/**
 * Module security: health/well-known/static are public; everything else requires a valid
 * portal-minted agent-call token (the identity bridge for both the portal agent and module UIs).
 * 401/403 use the JSON error envelope.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, AgentAuthenticationFilter agentFilter)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/healthz", "/.well-known/**", "/mfe/**", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, e) -> {
                          response.setStatus(401);
                          response.setContentType("application/json");
                          response.getWriter().write("{\"error\":\"authentication required\"}");
                        })
                    .accessDeniedHandler(
                        (request, response, e) -> {
                          response.setStatus(403);
                          response.setContentType("application/json");
                          response.getWriter().write("{\"error\":\"insufficient roles\"}");
                        }))
        .addFilterBefore(agentFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** Prevents Boot from double-registering the filter for every request type. */
  @Bean
  public FilterRegistrationBean<AgentAuthenticationFilter> agentFilterRegistration(
      AgentAuthenticationFilter filter) {
    FilterRegistrationBean<AgentAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /** Shared HTTP client settings (mirrors the portal's HttpClientConfig). */
  @Bean
  public RestClientCustomizer restClientCustomizer() {
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(10))
            .withReadTimeout(Duration.ofSeconds(30));
    ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.jdk().build(settings);
    return builder -> builder.requestFactory(factory);
  }
}
