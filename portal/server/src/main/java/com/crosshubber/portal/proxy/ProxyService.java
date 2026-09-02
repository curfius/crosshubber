package com.crosshubber.portal.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * MFE asset proxy — mirrors {@code portal/src/modules/proxy/proxy.routes.ts}: resolves the module's
 * {@code mfe} entry point (60 s in-memory cache), then fetches {@code <entryUrl-origin>/<rest>}
 * server-side and pipes body + content-type.
 */
@Service
public class ProxyService {

  private static final Duration CACHE_TTL = Duration.ofSeconds(60);

  private final EntryPointRepository entryPointRepo;
  private final Cache<String, Optional<EntryPointEntity>> epCache;
  private final HttpClient httpClient;

  public ProxyService(EntryPointRepository entryPointRepo) {
    this.entryPointRepo = entryPointRepo;
    this.epCache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).build();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public record FetchResult(int status, String contentType, byte[] body) {}

  /** Resolves the module's mfe entry point (cached 60 s, only successful hits). */
  public EntryPointEntity findMfeEntryPoint(String moduleKey) {
    return epCache
        .get(
            moduleKey,
            key ->
                entryPointRepo.findByModuleKey(key).stream()
                    .filter(ep -> "mfe".equals(ep.getType()))
                    .findFirst())
        .orElse(null);
  }

  /** Fetches the upstream asset; returns status, content-type and body. */
  public FetchResult fetch(EntryPointEntity entryPoint, String rest) throws Exception {
    URI entryUrl = URI.create(entryPoint.getEntryUrl());
    URI origin = new URI(entryUrl.getScheme(), entryUrl.getAuthority(), "/", null, null);
    URI target = origin.resolve("/" + rest);
    HttpRequest request =
        HttpRequest.newBuilder().uri(target).timeout(Duration.ofSeconds(30)).GET().build();
    HttpResponse<byte[]> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    String contentType = response.headers().firstValue("content-type").orElse(null);
    return new FetchResult(response.statusCode(), contentType, response.body());
  }
}
