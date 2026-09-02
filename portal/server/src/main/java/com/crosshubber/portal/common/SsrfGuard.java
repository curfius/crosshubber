package com.crosshubber.portal.common;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * SSRF guard for outbound fetches (manifest fetcher, MFE proxy).
 *
 * <p>Mirrors {@code portal/src/modules/registry/manifest.fetcher.ts} assertNotPrivate: blocks
 * loopback, RFC1918, link-local, ULA and 0.0.0.0 unless private access is explicitly allowed.
 */
public final class SsrfGuard {

  private SsrfGuard() {}

  /**
   * Asserts the URL is safe to fetch server-side.
   *
   * @param rawUrl absolute http(s) URL
   * @param allowPrivate when true, private addresses are permitted (dev mode)
   * @throws ResponseStatusException 400 for malformed URLs, 403 for blocked hosts
   */
  public static void assertSafeUrl(String rawUrl, boolean allowPrivate) {
    URI uri;
    try {
      uri = URI.create(rawUrl);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid url");
    }
    String scheme = uri.getScheme();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only http(s) urls are allowed");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid url");
    }
    String lower = host.toLowerCase();
    if (lower.equals("localhost")
        || lower.equals("0.0.0.0")
        || lower.endsWith(".localhost")
        || lower.endsWith(".local")
        || lower.endsWith(".internal")) {
      if (!allowPrivate) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host is not allowed");
      }
      return;
    }
    if (allowPrivate) {
      return;
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot resolve host");
    }
    for (InetAddress address : addresses) {
      if (isPrivate(address)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host is not allowed");
      }
    }
  }

  private static boolean isPrivate(InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isAnyLocalAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      // Unique local addresses fc00::/7
      if ((bytes[0] & 0xfe) == 0xfc) {
        return true;
      }
    }
    return false;
  }
}
