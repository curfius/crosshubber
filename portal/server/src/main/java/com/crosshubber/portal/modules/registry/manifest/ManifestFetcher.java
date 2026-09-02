package com.crosshubber.portal.modules.registry.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.SsrfGuard;
import com.crosshubber.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manifest fetcher — mirrors {@code portal/src/modules/registry/manifest.fetcher.ts}: 5 s timeout,
 * 1 MB body cap, SSRF protection, {@code .well-known} resolution.
 */
@Service
public class ManifestFetcher {

  private static final int MAX_BODY_BYTES = 1_048_576;
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

  private final PortalProperties props;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ManifestFetcher(PortalProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** Fetches {@code {baseUrl}/.well-known/portal-module.json}. */
  public JsonNode fetchManifestFromWellKnown(String baseUrl) throws Exception {
    URI base = URI.create(baseUrl);
    URI url = base.resolve("/.well-known/portal-module.json");
    return fetch(url);
  }

  /**
   * Fetches a manifest URL — appends {@code /.well-known/portal-module.json} unless the path
   * already ends in {@code .json} or contains {@code well-known}.
   */
  public JsonNode fetchManifestFromUrl(String urlString) throws Exception {
    URI url = URI.create(urlString);
    String path = url.getPath() == null ? "" : url.getPath();
    boolean isManifestPath = path.endsWith(".json") || path.contains("well-known");
    if (!isManifestPath) {
      String trimmed = path.replaceAll("/+$", "");
      url = url.resolve(trimmed + "/.well-known/portal-module.json");
    }
    return fetch(url);
  }

  private JsonNode fetch(URI url) throws IOException, InterruptedException {
    SsrfGuard.assertSafeUrl(url.toString(), props.isSsrfAllowPrivate());
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(url)
            .timeout(FETCH_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("fetch " + url.getPath() + " returned " + response.statusCode());
      }
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new IOException("manifest body exceeds 1MB limit");
      }
      return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
