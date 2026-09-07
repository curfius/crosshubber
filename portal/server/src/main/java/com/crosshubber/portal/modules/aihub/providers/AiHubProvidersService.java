package com.crosshubber.portal.modules.aihub.providers;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.dto.ProviderDto;
import com.crosshubber.portal.modules.aihub.dto.TokenDto;
import com.crosshubber.portal.modules.aihub.util.JsonUtils;
import com.crosshubber.portal.security.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;

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

  @Transactional(readOnly = true)
  public List<ProviderDto> getAll() {
    return providerRepo.findAll(Sort.by(Sort.Order.asc("name"))).stream()
        .map(this::toProviderDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public AiHubProviderEntity findProvider(String id) {
    return providerRepo.findById(id).orElse(null);
  }

  @Transactional(readOnly = true)
  public List<AiHubTokenEntity> tokenEntities(String providerId) {
    return tokenRepo.findByProviderIdOrderByNameAsc(providerId);
  }

  @Transactional(readOnly = true)
  public ResolvedKey resolveApiKey(String providerId, String tokenId) {
    AiHubProviderEntity provider = providerRepo.findById(providerId).orElse(null);
    if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
      return null;
    }
    AiHubTokenEntity token = resolveToken(providerId, tokenId);
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

  @Transactional
  public ProviderDto updateProvider(String id, Boolean enabled, String name, String baseUrl) {
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
    return toProviderDto(provider);
  }

  @Transactional
  public TokenDto addToken(String providerId, String name, String apiKey) {
    String encrypted = apiKey != null ? cryptoService.encryptApiKey(apiKey) : null;
    AiHubTokenEntity token = new AiHubTokenEntity();
    token.setId("tok_" + UUID.randomUUID());
    token.setProviderId(providerId);
    token.setName(name);
    token.setEncryptedKey(encrypted);
    token.setEnabled(true);
    token.setModels("[]");
    tokenRepo.save(token);
    return toTokenDto(token, apiKey);
  }

  @Transactional
  public TokenDto updateToken(
      String tokenId, String name, String providedApiKey, Boolean enabled, List<Object> models) {
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
      token.setModels(JsonUtils.writeJson(objectMapper, models));
    }
    tokenRepo.save(token);
    return toTokenDto(token, null);
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
  public ProviderDto addProvider(String id, String name, String baseUrl) {
    if (providerRepo.existsById(id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "provider already exists");
    }
    AiHubProviderEntity provider = new AiHubProviderEntity();
    provider.setId(id);
    provider.setName(name);
    provider.setEnabled(true);
    provider.setBaseUrl(baseUrl == null ? "" : baseUrl);
    providerRepo.save(provider);
    return toProviderDto(provider);
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

  private AiHubTokenEntity resolveToken(String providerId, String tokenId) {
    if (tokenId != null) {
      return tokenRepo.findById(tokenId).orElse(null);
    }
    return tokenRepo.findByProviderIdOrderByNameAsc(providerId).stream()
        .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
        .findFirst()
        .orElse(null);
  }

  private ProviderDto toProviderDto(AiHubProviderEntity p) {
    List<TokenDto> tokens =
        tokenRepo.findByProviderIdOrderByNameAsc(p.getId()).stream().map(this::toTokenDto).toList();
    return new ProviderDto(p.getId(), p.getName(), p.getEnabled(), p.getBaseUrl(), tokens);
  }

  private TokenDto toTokenDto(AiHubTokenEntity t) {
    return toTokenDto(t, null);
  }

  private TokenDto toTokenDto(AiHubTokenEntity t, String plainApiKey) {
    String masked = null;
    if (plainApiKey != null) {
      masked = cryptoService.maskApiKey(plainApiKey);
    } else if (t.getEncryptedKey() != null) {
      try {
        masked = cryptoService.maskApiKey(cryptoService.decryptApiKey(t.getEncryptedKey()));
      } catch (Exception e) {
        masked = null;
      }
    }
    return new TokenDto(
        t.getId(),
        t.getName(),
        masked,
        t.getEnabled(),
        JsonUtils.parseList(objectMapper, t.getModels()));
  }
}
