package com.crosshubber.portal.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;

import jakarta.servlet.http.HttpServletRequest;

/** Proxy controller envelope semantics: bad path 400, unknown module 404, passthrough, 502. */
class ProxyControllerTest {

  private ProxyService proxyService;
  private ProxyController controller;

  @BeforeEach
  void setUp() {
    proxyService = mock(ProxyService.class);
    controller = new ProxyController(proxyService);
  }

  private static HttpServletRequest request(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getHeader("X-Portal-Agent")).thenReturn("tok");
    when(request.getContentType()).thenReturn(null);
    return request;
  }

  private static ModuleContentEntity content() {
    ModuleContentEntity ep = new ModuleContentEntity();
    ep.setEntryUrl("http://solutions:8090/mfe/solutions.js");
    return ep;
  }

  @Test
  void emptyRestIsBadRequest() {
    HttpServletRequest request = request("/api/mfe/solutions/");
    assertEquals(400, controller.proxyGet("solutions", request).getStatusCode().value());
  }

  @Test
  void traversalIsBadRequest() {
    assertEquals(
        400,
        controller
            .proxyGet("solutions", request("/api/mfe/solutions/../x"))
            .getStatusCode()
            .value());
    assertEquals(
        400,
        controller
            .proxyGet("solutions", request("/api/mfe/solutions/%2e%2e/x"))
            .getStatusCode()
            .value());
  }

  @Test
  void unknownModuleIs404Envelope() {
    when(proxyService.findMfeContent("solutions")).thenReturn(null);
    ResponseEntity<?> response =
        controller.proxyGet("solutions", request("/api/mfe/solutions/mfe/main.js"));
    assertEquals(404, response.getStatusCode().value());
    assertEquals("no such mfe module", ((java.util.Map<?, ?>) response.getBody()).get("error"));
  }

  @Test
  void upstreamStatusAndBodyPassThrough() throws Exception {
    when(proxyService.findMfeContent("solutions")).thenReturn(content());
    when(proxyService.forward(any(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(
            new ProxyService.FetchResult(
                401,
                "application/json",
                "{\"error\":\"bad token\"}".getBytes(StandardCharsets.UTF_8)));

    ResponseEntity<?> response =
        controller.proxyGet("solutions", request("/api/mfe/solutions/api/projects"));

    assertEquals(401, response.getStatusCode().value());
    assertEquals(
        "{\"error\":\"bad token\"}",
        new String((byte[]) response.getBody(), StandardCharsets.UTF_8));
    Mockito.verify(proxyService)
        .forward(
            any(), Mockito.eq("api/projects"), Mockito.eq("GET"), any(), any(), Mockito.eq("tok"));
  }

  @Test
  void upstreamFailureIs502Envelope() throws Exception {
    when(proxyService.findMfeContent("solutions")).thenReturn(content());
    when(proxyService.forward(any(), anyString(), anyString(), any(), any(), anyString()))
        .thenThrow(new java.io.IOException("boom"));

    ResponseEntity<?> response =
        controller.proxyGet("solutions", request("/api/mfe/solutions/x.js"));

    assertEquals(502, response.getStatusCode().value());
    assertEquals("upstream fetch failed", ((java.util.Map<?, ?>) response.getBody()).get("error"));
  }

  @Test
  void postForwardsBodyAndMethod() throws Exception {
    when(proxyService.findMfeContent("solutions")).thenReturn(content());
    when(proxyService.forward(any(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(
            new ProxyService.FetchResult(
                201, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));

    HttpServletRequest request = request("/api/mfe/solutions/api/projects");
    ResponseEntity<?> response =
        controller.proxyPost(
            "solutions", request, "{\"name\":\"X\"}".getBytes(StandardCharsets.UTF_8));

    assertEquals(201, response.getStatusCode().value());
    Mockito.verify(proxyService)
        .forward(
            any(), Mockito.eq("api/projects"), Mockito.eq("POST"), any(), any(), Mockito.eq("tok"));
  }

  @Test
  void wrongPrefixIsBadRequest() {
    HttpServletRequest request = request("/api/mfe/other/x");
    assertEquals(400, controller.proxyGet("solutions", request).getStatusCode().value());
  }
}
