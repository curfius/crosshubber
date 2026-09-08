package com.crosshubber.portal.modules.aihub.providers;

import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.dto.CreateProviderRequest;
import com.crosshubber.portal.modules.aihub.dto.CreateTokenRequest;
import com.crosshubber.portal.modules.aihub.dto.ModelDto;
import com.crosshubber.portal.modules.aihub.dto.ProviderDto;
import com.crosshubber.portal.modules.aihub.dto.TokenDto;
import com.crosshubber.portal.modules.aihub.dto.UpdateProviderRequest;
import com.crosshubber.portal.modules.aihub.dto.UpdateTokenRequest;

@RestController
public class AiHubProvidersController {

  private final AiHubProvidersService providersService;
  private final RestClient restClient;

  public AiHubProvidersController(AiHubProvidersService providersService) {
    this.providersService = providersService;
    this.restClient =
        RestClient.builder()
            .requestFactory(
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                    java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()))
            .build();
  }

  @GetMapping({"/api/ai-hub/providers"})
  public Map<String, Object> list() {
    return Map.of("providers", providersService.getAll());
  }

  @PutMapping("/api/ai-hub/providers/{id}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ProviderDto update(@PathVariable String id, @RequestBody UpdateProviderRequest body) {
    return providersService.updateProvider(id, body.enabled(), body.name(), body.baseURL());
  }

  @PostMapping("/api/ai-hub/providers/{id}/tokens")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> addToken(@PathVariable String id, @RequestBody CreateTokenRequest body) {
    if (body.name() == null || body.name().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    if (providersService.findProvider(id) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
    }
    return ResponseEntity.status(201)
        .body(providersService.addToken(id, body.name(), body.apiKey()));
  }

  @PutMapping("/api/ai-hub/providers/{id}/tokens/{tokenId}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public TokenDto updateToken(
      @PathVariable String id, @PathVariable String tokenId, @RequestBody UpdateTokenRequest body) {
    return providersService.updateToken(
        tokenId, body.name(), body.apiKey(), body.enabled(), body.models());
  }

  @DeleteMapping("/api/ai-hub/providers/{id}/tokens/{tokenId}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> removeToken(@PathVariable String id, @PathVariable String tokenId) {
    if (!providersService.removeToken(tokenId)) {
      return ResponseEntity.status(404).body(Map.of("error", "token not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @SuppressWarnings("unchecked")
  @GetMapping("/api/ai-hub/providers/{id}/models")
  public ResponseEntity<?> models(@PathVariable String id) {
    AiHubProvidersService.ResolvedKey resolved = providersService.resolveApiKey(id, null);
    if (resolved == null) {
      var provider = providersService.findProvider(id);
      if (provider == null) {
        return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
      }
      if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error", "provider has no base URL"));
      }
      resolved = new AiHubProvidersService.ResolvedKey(null, provider.getBaseUrl());
    }
    final AiHubProvidersService.ResolvedKey finalResolved = resolved;
    String url = resolved.baseURL().replaceAll("/+$", "") + "/models";
    try {
      var request =
          restClient
              .get()
              .uri(url)
              .headers(
                  h -> {
                    h.set("Content-Type", "application/json");
                    if (finalResolved.apiKey() != null) {
                      h.setBearerAuth(finalResolved.apiKey());
                    }
                  });
      Map<String, Object> data = request.retrieve().body(Map.class);
      List<ModelDto> models = new ArrayList<>();
      if (data != null && data.get("data") instanceof List<?> upstreamModels) {
        for (Object item : upstreamModels) {
          if (item instanceof Map<?, ?> m) {
            Object upstreamId = m.get("id");
            Object rawName = m.get("name");
            if (upstreamId != null) {
              models.add(
                  new ModelDto(
                      String.valueOf(upstreamId),
                      rawName != null ? String.valueOf(rawName) : String.valueOf(upstreamId),
                      false));
            }
          }
        }
      }
      return ResponseEntity.ok(Map.of("models", models));
    } catch (Exception e) {
      return ResponseEntity.status(502)
          .body(Map.of("error", "failed to reach provider: " + e.getMessage()));
    }
  }

  @PostMapping({"/api/ai-hub/providers"})
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ProviderDto create(@RequestBody CreateProviderRequest body) {
    if (body.id() == null
        || body.name() == null
        || !body.id().matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "id and name are required, id must be kebab-case");
    }
    return providersService.addProvider(
        body.id(), body.name(), body.baseURL() != null ? body.baseURL() : "");
  }

  @DeleteMapping("/api/ai-hub/providers/{id}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> remove(@PathVariable String id) {
    if (!providersService.removeProvider(id)) {
      return ResponseEntity.status(404).body(Map.of("error", "provider not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
