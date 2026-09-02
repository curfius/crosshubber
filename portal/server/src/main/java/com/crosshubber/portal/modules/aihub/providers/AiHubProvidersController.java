package com.crosshubber.portal.modules.aihub.providers;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI provider routes — mirrors {@code portal/src/modules/ai-hub/providers.routes.ts}. Writes
 * require {@code portal-ai-hub-edit}. Also serves the deprecated {@code /api/llm/providers...} shim
 * (Deprecation headers).
 */
@RestController
public class AiHubProvidersController {

  private static final String LEGACY_BASE = "/api/llm";

  private final AiHubProvidersService providersService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AiHubProvidersController(
      AiHubProvidersService providersService, ObjectMapper objectMapper) {
    this.providersService = providersService;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  }

  @GetMapping({"/api/ai-hub/providers", LEGACY_BASE + "/providers"})
  public Map<String, Object> list() {
    return providersService.getAll();
  }

  @PutMapping({"/api/ai-hub/providers/{id}", LEGACY_BASE + "/providers/{id}"})
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
    Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
    String name = body.get("name") instanceof String s ? s : null;
    String baseURL = body.get("baseURL") instanceof String s ? s : null;
    return ResponseEntity.ok(providersService.updateProvider(id, enabled, name, baseURL));
  }

  @PostMapping({"/api/ai-hub/providers/{id}/tokens", LEGACY_BASE + "/providers/{id}/tokens"})
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> addToken(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    if (!(body.get("name") instanceof String name) || name.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    if (providersService.findProvider(id) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
    }
    String apiKey = body.get("apiKey") instanceof String s ? s : null;
    return ResponseEntity.status(201).body(providersService.addToken(id, name, apiKey));
  }

  @PutMapping({
    "/api/ai-hub/providers/{id}/tokens/{tokenId}",
    LEGACY_BASE + "/providers/{id}/tokens/{tokenId}"
  })
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> updateToken(
      @PathVariable String id,
      @PathVariable String tokenId,
      @RequestBody Map<String, Object> body) {
    String name = body.get("name") instanceof String s ? s : null;
    // Presence matters: an explicit empty apiKey clears the stored key.
    String apiKey = body.containsKey("apiKey") && body.get("apiKey") instanceof String s ? s : null;
    Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
    List<Object> models = body.get("models") instanceof List<?> l ? List.copyOf(l) : null;
    return ResponseEntity.ok(providersService.updateToken(tokenId, name, apiKey, enabled, models));
  }

  @DeleteMapping({
    "/api/ai-hub/providers/{id}/tokens/{tokenId}",
    LEGACY_BASE + "/providers/{id}/tokens/{tokenId}"
  })
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> removeToken(@PathVariable String id, @PathVariable String tokenId) {
    if (!providersService.removeToken(tokenId)) {
      return ResponseEntity.status(404).body(Map.of("error", "token not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @GetMapping({"/api/ai-hub/providers/{id}/models", LEGACY_BASE + "/providers/{id}/models"})
  public ResponseEntity<?> models(@PathVariable String id) {
    AiHubProvidersService.ResolvedKey resolved = providersService.resolveApiKey(id, null);
    if (resolved == null) {
      var provider = providersService.findProvider(id);
      if (provider == null) {
        return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
      }
      if (provider.getBaseUrl() == null) {
        return ResponseEntity.badRequest().body(Map.of("error", "provider has no base URL"));
      }
      resolved = new AiHubProvidersService.ResolvedKey(null, provider.getBaseUrl());
    }
    String url = resolved.baseURL().replaceAll("/+$", "") + "/models";
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder()
              .uri(java.net.URI.create(url))
              .timeout(java.time.Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .GET();
      if (resolved.apiKey() != null) {
        requestBuilder.header("Authorization", "Bearer " + resolved.apiKey());
      }
      HttpResponse<String> upstream =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
        return ResponseEntity.status(502)
            .body(Map.of("error", "provider API returned " + upstream.statusCode()));
      }
      Map<String, Object> data =
          objectMapper.readValue(
              upstream.body(),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      List<Map<String, Object>> models = new java.util.ArrayList<>();
      if (data.get("data") instanceof List<?> upstreamModels) {
        for (Object item : upstreamModels) {
          if (item instanceof Map<?, ?> m) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", String.valueOf(m.get("id")));
            model.put(
                "name",
                m.get("name") != null
                    ? String.valueOf(m.get("name"))
                    : String.valueOf(m.get("id")));
            model.put("enabled", false);
            models.add(model);
          }
        }
      }
      return ResponseEntity.ok(Map.of("models", models));
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      return ResponseEntity.status(502)
          .body(Map.of("error", "provider API returned " + e.getStatusCode().value()));
    } catch (Exception e) {
      return ResponseEntity.status(502)
          .body(Map.of("error", "failed to reach provider: " + e.getMessage()));
    }
  }

  @PostMapping({"/api/ai-hub/providers", LEGACY_BASE + "/providers"})
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
    Object idObj = body.get("id");
    Object nameObj = body.get("name");
    if (!(idObj instanceof String id) || !(nameObj instanceof String name)) {
      return ResponseEntity.badRequest().body(Map.of("error", "id and name are required"));
    }
    if (!id.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
      return ResponseEntity.badRequest().body(Map.of("error", "id must be kebab-case"));
    }
    String baseURL = body.get("baseURL") instanceof String s ? s : "";
    Map<String, Object> created = providersService.addProvider(id, name, baseURL);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @DeleteMapping({"/api/ai-hub/providers/{id}", LEGACY_BASE + "/providers/{id}"})
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> remove(@PathVariable String id) {
    if (!providersService.removeProvider(id)) {
      return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
