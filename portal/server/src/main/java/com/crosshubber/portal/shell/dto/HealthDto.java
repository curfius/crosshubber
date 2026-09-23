package com.crosshubber.portal.shell.dto;

/** {@code GET /healthz} payload. */
public record HealthDto(boolean ok, String app, String db) {}
