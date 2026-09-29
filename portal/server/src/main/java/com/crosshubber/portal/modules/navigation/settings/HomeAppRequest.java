package com.crosshubber.portal.modules.navigation.settings;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.constraints.Pattern;

/** {@code PUT /api/settings} body â€” only {@code homeApp} is accepted. */
public record HomeAppRequest(
    @Pattern(regexp = Keys.REF_RE, message = "must be a ref of the form \"moduleKey:entryKey\"")
        String homeApp) {}
