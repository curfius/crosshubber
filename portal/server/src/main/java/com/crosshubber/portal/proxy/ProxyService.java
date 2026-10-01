package com.crosshubber.portal.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * MFE proxy: resolves the module's {@code mfe} content (60 s in-memory cache), then forwards {@code
 * <entryUrl-origin>/<rest>} server-side and pipes body + content-type. GET serves module assets
 * (and API reads); POST/PATCH forward MFE data calls (JSON body + {@code X-Portal-Agent}
 * passthrough), so elements never need the module's container hostname or CORS.
 */
@Service
public class ProxyService {

  private static final Duration CACHE_TTL = Duration.ofSeconds(60);

  private final ModuleContentRepository entryPointRepo;
  private final Cache<String, Optional<ModuleContentEntity>> epCache;
  private final HttpClient httpClient;

  public ProxyService(ModuleContentRepository entryPointRepo) {
    this.entryPointRepo = entryPointRepo;
    this.epCache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).build();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public record FetchResult(int status, String contentType, byte[] body) {}

  /** Resolves the module's mfe module content (cached 60 s, only successful hits). */
  public ModuleContentEntity findMfeContent(String moduleKey) {
    return epCache
        .get(
            moduleKey,
            key ->
                entryPointRepo.findByModuleKey(key).stream()
                    .filter(ep -> ep.getType() == ModuleContentType.MFE)
                    .findFirst())
        .orElse(null);
  }

  /** Fetches the upstream asset (GET, no body, no agent token). */
  public FetchResult fetch(ModuleContentEntity entryPoint, String rest) throws Exception {
    return forward(entryPoint, rest, "GET", null, null, null);
  }

  /** Forwards an MFE call to the module backend; agent token + body passthrough optional. */
  public FetchResult forward(
      ModuleContentEntity entryPoint,
      String rest,
      String method,
      byte[] body,
      String contentType,
      String agentToken)
      throws Exception {
    URI entryUrl = URI.create(entryPoint.getEntryUrl());
    URI origin = new URI(entryUrl.getScheme(), entryUrl.getAuthority(), "/", null, null);
    URI target = origin.resolve("/" + rest);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(target).timeout(Duration.ofSeconds(30));
    if (agentToken != null && !agentToken.isBlank()) {
      builder.header("X-Portal-Agent", agentToken);
    }
    if (contentType != null && !contentType.isBlank()) {
      builder.header("Content-Type", contentType);
    }
    if ("GET".equals(method)) {
      builder.GET();
    } else {
      builder.method(
          method, HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
    }
    HttpResponse<byte[]> response =
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    String responseContentType = response.headers().firstValue("content-type").orElse(null);
    return new FetchResult(response.statusCode(), responseContentType, response.body());
  }
}
