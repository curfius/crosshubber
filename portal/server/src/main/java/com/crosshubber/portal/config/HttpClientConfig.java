package com.crosshubber.portal.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared HTTP client settings for every {@link RestClient} built through Boot's auto-configured
 * {@code RestClient.Builder} (Keycloak identity calls, Keycloak admin API, AI Hub provider model
 * listing).
 *
 * <p>Before this config, several call sites built clients with {@code RestClient.create()} — no
 * connect/read timeouts, so a hung upstream held request threads indefinitely. The JDK factory also
 * pairs well with the virtual-threads runtime (blocking calls run on virtual carriers).
 */
@Configuration
public class HttpClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  @Bean
  public RestClientCustomizer portalRestClientCustomizer() {
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(CONNECT_TIMEOUT)
            .withReadTimeout(READ_TIMEOUT);
    ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.jdk().build(settings);
    return builder -> builder.requestFactory(factory);
  }
}
