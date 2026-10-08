package com.crosshubber.portal.modules.registry.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.common.SsrfGuard;
import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.JsonNode;

/**
 * Manifest fetcher: timeout, 1 MB body cap, SSRF protection, {@code .well-known} resolution.
 *
 * <p>Every failure is reported as a {@link ManifestFetchException} carrying a {@link
 * ManifestFetchException.Code}, so the registry UI can show localized, cause-specific text instead
 * of a raw JDK message.
 */
@Service
public class ManifestFetcher {

  private static final int MAX_BODY_BYTES = 1_048_576;
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
  private static final String NOT_A_URL = "not a valid URL";

  private final PortalProperties props;
  private final JsonUtils json;
  private final Duration fetchTimeout;
  private final HttpClient httpClient;

  @Autowired
  public ManifestFetcher(PortalProperties props, JsonUtils json) {
    this(props, json, FETCH_TIMEOUT);
  }

  /** Test seam — lets the timeout case run in milliseconds instead of the production 5 s. */
  ManifestFetcher(PortalProperties props, JsonUtils json, Duration fetchTimeout) {
    this.props = props;
    this.json = json;
    this.fetchTimeout = fetchTimeout;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(fetchTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** Fetches {@code {baseUrl}/.well-known/portal-module.json}. */
  public JsonNode fetchManifestFromWellKnown(String baseUrl) throws ManifestFetchException {
    URI base = parse(baseUrl);
    URI url;
    try {
      url = base.resolve("/.well-known/portal-module.json");
    } catch (IllegalArgumentException e) {
      throw new ManifestFetchException(
          ManifestFetchException.Code.INVALID_URL, baseUrl, NOT_A_URL, e);
    }
    return fetch(url);
  }

  /**
   * Fetches a manifest URL — appends {@code /.well-known/portal-module.json} unless the path
   * already ends in {@code .json} or contains {@code well-known}.
   */
  public JsonNode fetchManifestFromUrl(String urlString) throws ManifestFetchException {
    URI url = parse(urlString);
    String path = url.getPath() == null ? "" : url.getPath();
    boolean isManifestPath = path.endsWith(".json") || path.contains("well-known");
    if (!isManifestPath) {
      String trimmed = path.replaceAll("/+$", "");
      try {
        url = url.resolve(trimmed + "/.well-known/portal-module.json");
      } catch (IllegalArgumentException e) {
        throw new ManifestFetchException(
            ManifestFetchException.Code.INVALID_URL, urlString, NOT_A_URL, e);
      }
    }
    return fetch(url);
  }

  private static URI parse(String raw) throws ManifestFetchException {
    try {
      return URI.create(raw);
    } catch (RuntimeException e) {
      throw new ManifestFetchException(ManifestFetchException.Code.INVALID_URL, raw, NOT_A_URL, e);
    }
  }

  private JsonNode fetch(URI url) throws ManifestFetchException {
    String raw = url.toString();
    try {
      SsrfGuard.assertSafeUrl(raw, props.isSsrfAllowPrivate());
    } catch (ResponseStatusException e) {
      throw new ManifestFetchException(ssrfCode(e), raw, e.getReason(), e);
    }

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(url)
            .timeout(fetchTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ManifestFetchException(ManifestFetchException.Code.FAILED, raw, "interrupted", e);
    } catch (IOException | RuntimeException e) {
      throw sendFailure(raw, e);
    }

    try (InputStream body = response.body()) {
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        throw new ManifestFetchException(
            ManifestFetchException.Code.UPSTREAM_STATUS, raw, "HTTP " + status, null);
      }
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new ManifestFetchException(
            ManifestFetchException.Code.TOO_LARGE, raw, "manifest body exceeds 1MB limit", null);
      }
      JsonNode parsed = json.parseTreeOrNull(new String(bytes, StandardCharsets.UTF_8));
      if (parsed == null) {
        throw new ManifestFetchException(
            ManifestFetchException.Code.NOT_JSON, raw, "response is not valid JSON", null);
      }
      return parsed;
    } catch (IOException e) {
      throw new ManifestFetchException(ManifestFetchException.Code.FAILED, raw, e.getMessage(), e);
    }
  }

  /** Guard failures are ours — the four reasons in {@link SsrfGuard} map to fetch codes. */
  private static ManifestFetchException.Code ssrfCode(ResponseStatusException e) {
    if (e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
      return ManifestFetchException.Code.BLOCKED;
    }
    return "cannot resolve host".equals(e.getReason())
        ? ManifestFetchException.Code.UNKNOWN_HOST
        : ManifestFetchException.Code.INVALID_URL;
  }

  private static ManifestFetchException sendFailure(String raw, Exception e) {
    ManifestFetchException.Code code = sendCode(e);
    return new ManifestFetchException(code, raw, sendDetail(code, e), e);
  }

  private static ManifestFetchException.Code sendCode(Exception e) {
    if (e instanceof HttpTimeoutException) {
      return ManifestFetchException.Code.TIMEOUT;
    }
    if (e instanceof UnknownHostException) {
      return ManifestFetchException.Code.UNKNOWN_HOST;
    }
    if (e instanceof ConnectException
        || e instanceof NoRouteToHostException
        || e instanceof SocketException
        || mentions(e, "refused")) {
      return ManifestFetchException.Code.UNREACHABLE;
    }
    return ManifestFetchException.Code.FAILED;
  }

  private static String sendDetail(ManifestFetchException.Code code, Exception e) {
    return switch (code) {
      case TIMEOUT -> "timed out";
      case UNKNOWN_HOST -> "cannot resolve host";
      case UNREACHABLE -> mentions(e, "refused") ? "connection refused" : "connection failed";
      default -> e.getMessage();
    };
  }

  private static boolean mentions(Exception e, String needle) {
    String message = e.getMessage();
    return message != null && message.toLowerCase(Locale.ROOT).contains(needle);
  }
}
