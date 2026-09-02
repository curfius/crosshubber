package com.crosshubber.portal.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Session payload stored in HMAC cookie.
 *
 * <p>Mirrors {@code SessionData} in {@code portal/src/types/index.ts} and {@code
 * portal/src/session.ts} cookie format: base64url(JSON).hmacSHA256(secret).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionData(PortalUser user, String idToken, String refreshToken, long exp) {}
