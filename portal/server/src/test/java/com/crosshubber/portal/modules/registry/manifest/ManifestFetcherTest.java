package com.crosshubber.portal.modules.registry.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.PortalProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Failure classification of {@link ManifestFetcher} against a local JDK {@link HttpServer}: every
 * mode must surface as the {@link ManifestFetchException.Code} the registry UI localizes.
 */
class ManifestFetcherTest {

  private static final byte[] MANIFEST =
      ("{\"manifestVersion\":1,\"key\":\"sample-sender\",\"name\":\"Sample Sender\","
              + "\"baseUrl\":\"http://sample-sender:8092\"}")
          .getBytes(StandardCharsets.UTF_8);

  private HttpServer server;
  private PortalProperties props;
  private ManifestFetcher fetcher;
  private CountDownLatch release;
  private String base;

  @BeforeEach
  void setUp() throws IOException {
    props = new PortalProperties();
    props.setSsrfAllowPrivate(true);
    fetcher =
        new ManifestFetcher(
            props, new JsonUtils(JsonMapper.builder().build()), Duration.ofMillis(400));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    release = new CountDownLatch(1);
    base = "http://localhost:" + server.getAddress().getPort();
    server.start();
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    server.stop(0);
  }

  @Test
  void urlWithoutManifestPathGetsWellKnownSuffixAndParses() throws Exception {
    AtomicReference<String> requested = new AtomicReference<>();
    server.createContext(
        "/.well-known/portal-module.json",
        exchange -> {
          requested.set(exchange.getRequestURI().getPath());
          respond(exchange, 200, MANIFEST);
        });

    JsonNode manifest = fetcher.fetchManifestFromUrl(base + "/");

    assertEquals("sample-sender", manifest.get("key").asString());
    assertEquals("/.well-known/portal-module.json", requested.get());
  }

  @Test
  void wellKnownLookupResolvesAgainstTheBasePath() throws Exception {
    server.createContext(
        "/.well-known/portal-module.json", exchange -> respond(exchange, 200, MANIFEST));

    JsonNode manifest = fetcher.fetchManifestFromWellKnown(base + "/some/base/");

    assertEquals("sample-sender", manifest.get("key").asString());
  }

  @Test
  void non2xxUpstreamIsUpstreamStatus() {
    server.createContext(
        "/.well-known/portal-module.json",
        exchange -> respond(exchange, 404, "not here".getBytes(StandardCharsets.UTF_8)));

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(base + "/"));

    assertEquals(ManifestFetchException.Code.UPSTREAM_STATUS, e.code());
    assertEquals("HTTP 404", e.detail());
    assertTrue(e.getMessage().contains(base), e.getMessage());
  }

  @Test
  void nonJsonBodyIsNotJson() {
    server.createContext(
        "/.well-known/portal-module.json",
        exchange -> respond(exchange, 200, "<html>nope</html>".getBytes(StandardCharsets.UTF_8)));

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(base + "/"));

    assertEquals(ManifestFetchException.Code.NOT_JSON, e.code());
    assertEquals("response is not valid JSON", e.detail());
  }

  @Test
  void oversizedBodyIsTooLarge() {
    byte[] huge = new byte[1_048_577];
    server.createContext(
        "/.well-known/portal-module.json", exchange -> respond(exchange, 200, huge));

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(base + "/"));

    assertEquals(ManifestFetchException.Code.TOO_LARGE, e.code());
  }

  @Test
  void connectionRefusedIsUnreachable() throws IOException {
    int freePort;
    try (ServerSocket socket = new ServerSocket(0)) {
      freePort = socket.getLocalPort();
    }
    String url = "http://localhost:" + freePort + "/.well-known/portal-module.json";

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(url));

    assertEquals(ManifestFetchException.Code.UNREACHABLE, e.code());
    assertTrue(e.getMessage().contains(url), e.getMessage());
  }

  @Test
  void hangingUpstreamIsTimeout() {
    server.createContext(
        "/.well-known/portal-module.json",
        exchange -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(base + "/"));

    assertEquals(ManifestFetchException.Code.TIMEOUT, e.code());
  }

  @Test
  void privateHostIsBlockedWhenNotAllowed() {
    props.setSsrfAllowPrivate(false);

    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl(base + "/"));

    assertEquals(ManifestFetchException.Code.BLOCKED, e.code());
  }

  @Test
  void unparseableUrlIsInvalidUrl() {
    ManifestFetchException e =
        assertThrows(ManifestFetchException.class, () -> fetcher.fetchManifestFromUrl("ht tp://x"));

    assertEquals(ManifestFetchException.Code.INVALID_URL, e.code());
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
