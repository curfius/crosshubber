package com.crosshubber.portal.proxy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;

import jakarta.servlet.http.HttpServletRequest;

/** MFE asset proxy route — mirrors GET /api/mfe/:key/* in proxy.routes.ts. */
@RestController
public class ProxyController {

  private final ProxyService proxyService;

  public ProxyController(ProxyService proxyService) {
    this.proxyService = proxyService;
  }

  @GetMapping("/api/mfe/{key}/**")
  public ResponseEntity<?> proxy(@PathVariable String key, HttpServletRequest request) {
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
    EntryPointEntity entryPoint = proxyService.findMfeEntryPoint(key);
    if (entryPoint == null || entryPoint.getEntryUrl() == null) {
      return ResponseEntity.status(404).body(java.util.Map.of("error", "no such mfe module"));
    }
    try {
      ProxyService.FetchResult upstream = proxyService.fetch(entryPoint, rest);
      if (upstream.status() < 200 || upstream.status() >= 300) {
        return ResponseEntity.status(upstream.status()).build();
      }
      ResponseEntity.BodyBuilder builder = ResponseEntity.ok().header("cache-control", "no-cache");
      if (upstream.contentType() != null) {
        builder.header("Content-Type", upstream.contentType());
      }
      return builder.body(upstream.body());
    } catch (Exception e) {
      return ResponseEntity.status(502).body(java.util.Map.of("error", "upstream fetch failed"));
    }
  }
}
