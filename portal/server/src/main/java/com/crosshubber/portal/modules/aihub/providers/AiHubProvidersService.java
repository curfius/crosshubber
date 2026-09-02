package com.crosshubber.portal.modules.aihub.providers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.security.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI provider/token configuration — mirrors {@code
 * portal/src/modules/ai-hub/providers.repository.ts}: keys are stored AES-256-GCM encrypted and
 * returned masked.
 */
@Service
public class AiHubProvidersService {

  private final AiHubProviderRepository providerRepo;
  private final AiHubTokenRepository tokenRepo;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;

  public AiHubProvidersService(
      AiHubProviderRepository providerRepo,
      AiHubTokenRepository tokenRepo,
      CryptoService cryptoService,
      ObjectMapper objectMapper) {
    this.providerRepo = providerRepo;
    this.tokenRepo = tokenRepo;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
  }

  // ── Queries ──────────────────────────────────────────────────────────

  /** Providers with their tokens (keys masked) — mirrors getAll(). */
  @Transactional(readOnly = true)
  public Map<String, Object> getAll() {
    List<Map<String, Object>> providers = new java.util.ArrayList<>();
    for (AiHubProviderEntity p : providerRepo.findAll(Sort.by(Sort.Order.asc("name")))) {
      providers.add(providerDto(p));
    }
    return Map.of("providers", providers);
  }

  @Transactional(readOnly = true)
  public AiHubProviderEntity findProvider(String id) {
    return providerRepo.findById(id).orElse(null);
  }

  /** Tokens of a provider ordered by name (for the pipeline model binding). */
  @Transactional(readOnly = true)
  public List<AiHubTokenEntity> tokenEntities(String providerId) {
    return tokenRepo.findByProviderIdOrderByNameAsc(providerId);
  }

  /** Decrypts a token's API key; null when absent. */
  @Transactional(readOnly = true)
  public String getDecryptedKey(String tokenId) {
    AiHubTokenEntity token = tokenRepo.findById(tokenId).orElse(null);
    if (token == null || token.getEncryptedKey() == null) {
      return null;
    }
    return cryptoService.decryptApiKey(token.getEncryptedKey());
  }

  /**
   * Resolves the API key for a provider: explicit tokenId, else the first enabled token. Returns
   * {apiKey, baseURL} or null.
   */
  @Transactional(readOnly = true)
  public ResolvedKey resolveApiKey(String providerId, String tokenId) {
    AiHubProviderEntity provider = providerRepo.findById(providerId).orElse(null);
    if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
      return null;
    }
    AiHubTokenEntity token = null;
    if (tokenId != null) {
      token = tokenRepo.findById(tokenId).orElse(null);
    } else {
      token =
          tokenRepo.findByProviderIdOrderByNameAsc(providerId).stream()
              .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
              .findFirst()
              .orElse(null);
    }
    if (token == null || token.getEncryptedKey() == null) {
      return null;
    }
    try {
      String apiKey = cryptoService.decryptApiKey(token.getEncryptedKey());
      return new ResolvedKey(apiKey, provider.getBaseUrl());
    } catch (Exception e) {
      return null;
    }
  }

  public record ResolvedKey(String apiKey, String baseURL) {}

  // ── Commands ─────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> updateProvider(
      String id, Boolean enabled, String name, String baseUrl) {
    // Node quirk: an empty patch (no recognized fields) → 404 even for existing rows
    // (providers.repository.ts:67 — sets.length === 0 → null).
    if (enabled == null && name == null && baseUrl == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found");
    }
    AiHubProviderEntity provider =
        providerRepo
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (enabled != null) {
      provider.setEnabled(enabled);
    }
    if (name != null) {
      provider.setName(name);
    }
    if (baseUrl != null) {
      provider.setBaseUrl(baseUrl);
    }
    providerRepo.save(provider);
    return providerDto(provider);
  }

  @Transactional
  public Map<String, Object> addToken(String providerId, String name, String apiKey) {
    String encrypted = apiKey != null ? cryptoService.encryptApiKey(apiKey) : null;
    AiHubTokenEntity token = new AiHubTokenEntity();
    token.setId("tok_" + UUID.randomUUID().toString().substring(0, 8));
    token.setProviderId(providerId);
    token.setName(name);
    token.setEncryptedKey(encrypted);
    token.setEnabled(true);
    token.setModels("[]");
    tokenRepo.save(token);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", token.getId());
    out.put("name", token.getName());
    out.put("maskedKey", apiKey != null ? cryptoService.maskApiKey(apiKey) : null);
    out.put("enabled", token.getEnabled());
    out.put("models", List.of());
    return out;
  }

  /**
   * Partial token update. {@code providedApiKey} is null when the field was absent from the
   * request; an empty string clears the stored key.
   */
  @Transactional
  public Map<String, Object> updateToken(
      String tokenId, String name, String providedApiKey, Boolean enabled, List<Object> models) {
    // Node quirk: an empty patch (no recognized fields) → 404 even for existing rows
    // (providers.repository.ts:86 — sets.length === 0 → null).
    if (name == null && providedApiKey == null && enabled == null && models == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
    }
    AiHubTokenEntity token =
        tokenRepo
            .findById(tokenId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found"));
    if (name != null) {
      token.setName(name);
    }
    if (providedApiKey != null) {
      token.setEncryptedKey(
          providedApiKey.isEmpty() ? null : cryptoService.encryptApiKey(providedApiKey));
    }
    if (enabled != null) {
      token.setEnabled(enabled);
    }
    if (models != null) {
      token.setModels(writeJson(models));
    }
    tokenRepo.save(token);
    return tokenDto(token);
  }

  @Transactional
  public boolean removeToken(String tokenId) {
    if (!tokenRepo.existsById(tokenId)) {
      return false;
    }
    tokenRepo.deleteById(tokenId);
    return true;
  }

  @Transactional
  public Map<String, Object> addProvider(String id, String name, String baseUrl) {
    if (providerRepo.existsById(id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "provider already exists");
    }
    AiHubProviderEntity provider = new AiHubProviderEntity();
    provider.setId(id);
    provider.setName(name);
    provider.setEnabled(true);
    provider.setBaseUrl(baseUrl == null ? "" : baseUrl);
    providerRepo.save(provider);
    return providerDto(provider);
  }

  @Transactional
  public boolean removeProvider(String id) {
    if (!providerRepo.existsById(id)) {
      return false;
    }
    tokenRepo.findByProviderId(id).forEach(tokenRepo::delete);
    providerRepo.deleteById(id);
    return true;
  }

  // ── DTOs ─────────────────────────────────────────────────────────────

  public Map<String, Object> providerDto(AiHubProviderEntity p) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", p.getId());
    out.put("name", p.getName());
    out.put("enabled", p.getEnabled());
    if (p.getBaseUrl() != null) {
      out.put("baseURL", p.getBaseUrl());
    }
    List<Map<String, Object>> tokens = new java.util.ArrayList<>();
    for (AiHubTokenEntity t : tokenRepo.findByProviderIdOrderByNameAsc(p.getId())) {
      tokens.add(tokenDto(t));
    }
    out.put("tokens", tokens);
    return out;
  }

  /** Token DTO — mirrors rowToTokenResponse (masked key). */
  public Map<String, Object> tokenDto(AiHubTokenEntity t) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", t.getId());
    out.put("name", t.getName());
    String masked = null;
    if (t.getEncryptedKey() != null) {
      try {
        masked = cryptoService.maskApiKey(cryptoService.decryptApiKey(t.getEncryptedKey()));
      } catch (Exception e) {
        masked = null;
      }
    }
    out.put("maskedKey", masked);
    out.put("enabled", t.getEnabled());
    out.put("models", parseModels(t.getModels()));
    return out;
  }

  private List<Map<String, Object>> parseModels(String json) {
    try {
      if (json == null || json.isBlank()) {
        return List.of();
      }
      return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("models serialization failed", e);
    }
  }
}
