package com.crosshubber.portal.proxy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;

import jakarta.servlet.http.HttpServletRequest;

/**
 * MFE proxy routes — mirrors GET/POST /api/mfe/:key/* in proxy.routes.ts. GET serves module assets;
 * POST/PATCH forward element data calls (body + {@code X-Portal-Agent} passthrough). Upstream
 * bodies pass through on every status so module error envelopes reach the element.
 */
@RestController
public class ProxyController {

  private final ProxyService proxyService;

  public ProxyController(ProxyService proxyService) {
    this.proxyService = proxyService;
  }

  @GetMapping("/api/mfe/{key}/**")
  public ResponseEntity<?> proxyGet(@PathVariable String key, HttpServletRequest request) {
    return proxy(key, request, "GET", null);
  }

  @PostMapping("/api/mfe/{key}/**")
  public ResponseEntity<?> proxyPost(
      @PathVariable String key,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body) {
    return proxy(key, request, "POST", body);
  }

  @PatchMapping("/api/mfe/{key}/**")
  public ResponseEntity<?> proxyPatch(
      @PathVariable String key,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body) {
    return proxy(key, request, "PATCH", body);
  }

  private ResponseEntity<?> proxy(
      String key, HttpServletRequest request, String method, byte[] body) {
    String uri = request.getRequestURI();
    String prefix = "/api/mfe/" + key + "/";
    if (!uri.startsWith(prefix)) {
      return ResponseEntity.badRequest().body(java.util.Map.of("error", "bad path"));
    }
    String rest = uri.substring(prefix.length());
    String decoded = URLDecoder.decode(rest, StandardCharsets.UTF_8);
    if (rest.isEmpty() || rest.contains("..") || decoded.contains("..")) {
      return ResponseEntity.badRequest().body(java.util.Map.of("error", "bad path"));
    }
    ModuleContentEntity content = proxyService.findMfeContent(key);
    if (content == null || content.getEntryUrl() == null) {
      return ResponseEntity.status(404).body(java.util.Map.of("error", "no such mfe module"));
    }
    try {
      ProxyService.FetchResult upstream =
          proxyService.forward(
              content,
              rest,
              method,
              body,
              request.getContentType(),
              request.getHeader("X-Portal-Agent"));
      ResponseEntity.BodyBuilder builder =
          ResponseEntity.status(upstream.status()).header("cache-control", "no-cache");
      if (upstream.contentType() != null) {
        builder.header("Content-Type", upstream.contentType());
      }
      return builder.body(upstream.body());
    } catch (Exception e) {
      return ResponseEntity.status(502).body(java.util.Map.of("error", "upstream fetch failed"));
    }
  }
}
