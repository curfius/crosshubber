package com.crosshubber.portal.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Proxy forwarding: path mapping, method/body/token passthrough, envelope semantics. */
class ProxyServiceTest {

  private static HttpServer server;
  private static int port;

  record Seen(
      String method,
      String path,
      String agentToken,
      String contentType,
      byte[] body,
      int status,
      byte[] response) {}

  private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/",
        (HttpExchange ex) -> {
          byte[] body;
          try (var in = ex.getRequestBody()) {
            body = in.readAllBytes();
          }
          SEEN.add(
              new Seen(
                  ex.getRequestMethod(),
                  ex.getRequestURI().toString(),
                  ex.getRequestHeaders().getFirst("X-Portal-Agent"),
                  ex.getRequestHeaders().getFirst("Content-Type"),
                  body,
                  200,
                  "upstream-body".getBytes(StandardCharsets.UTF_8)));
          byte[] response = "upstream-body".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, response.length);
          try (var out = ex.getResponseBody()) {
            out.write(response);
          }
        });
    server.start();
  }

  @AfterAll
  static void stopStub() {
    server.stop(0);
  }

  private static ModuleContentEntity entryPoint() {
    ModuleContentEntity ep = new ModuleContentEntity();
    ep.setEntryUrl("http://127.0.0.1:" + port + "/mfe/solutions.js");
    return ep;
  }

  @Test
  void getAssetMapsRestPathToOrigin() throws Exception {
    ProxyService service = new ProxyService(null);
    ProxyService.FetchResult result = service.fetch(entryPoint(), "mfe/main.js");
    assertEquals(200, result.status());
    assertArrayEquals("upstream-body".getBytes(StandardCharsets.UTF_8), result.body());
    Seen seen = SEEN.get(SEEN.size() - 1);
    assertEquals("GET", seen.method());
    assertEquals("/mfe/main.js", seen.path());
    assertEquals(null, seen.agentToken());
  }

  @Test
  void postDataForwardsBodyTokenAndContentType() throws Exception {
    ProxyService service = new ProxyService(null);
    ProxyService.FetchResult result =
        service.forward(
            entryPoint(),
            "api/projects",
            "POST",
            "{\"name\":\"X\"}".getBytes(StandardCharsets.UTF_8),
            "application/json",
            "agent-token-1");
    assertEquals(200, result.status());
    Seen seen = SEEN.get(SEEN.size() - 1);
    assertEquals("POST", seen.method());
    assertEquals("/api/projects", seen.path());
    assertEquals("agent-token-1", seen.agentToken());
    assertEquals("application/json", seen.contentType());
    assertEquals("{\"name\":\"X\"}", new String(seen.body(), StandardCharsets.UTF_8));
  }

  @Test
  void blankAgentTokenIsNotSent() throws Exception {
    ProxyService service = new ProxyService(null);
    service.forward(entryPoint(), "api/projects", "POST", new byte[0], null, " ");
    Seen seen = SEEN.get(SEEN.size() - 1);
    assertEquals(null, seen.agentToken());
  }

  @Test
  void getWithAgentTokenForwardsIt() throws Exception {
    ProxyService service = new ProxyService(null);
    service.forward(entryPoint(), "api/projects", "GET", null, null, "tok-2");
    Seen seen = SEEN.get(SEEN.size() - 1);
    assertEquals("GET", seen.method());
    assertEquals("tok-2", seen.agentToken());
  }

  @Test
  void nullBodyBecomesEmptyForNonGet() throws Exception {
    ProxyService service = new ProxyService(null);
    ProxyService.FetchResult result =
        service.forward(entryPoint(), "api/notes", "PATCH", null, null, "t");
    assertEquals(200, result.status());
    assertTrue(SEEN.get(SEEN.size() - 1).body().length == 0);
  }

  @Test
  void pathsResolveAgainstEntryUrlOrigin() throws Exception {
    ProxyService service = new ProxyService(null);
    ModuleContentEntity ep = entryPoint();
    ep.setEntryUrl("http://127.0.0.1:" + port);
    service.fetch(ep, "api/clients");
    assertEquals("/api/clients", SEEN.get(SEEN.size() - 1).path());
  }
}
