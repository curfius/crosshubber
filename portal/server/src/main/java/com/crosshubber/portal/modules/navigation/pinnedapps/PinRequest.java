package com.crosshubber.portal.modules.navigation.pinnedapps;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /api/navigation/pinned-apps} body (star-toggle an app ref). */
public record PinRequest(
    @NotBlank(message = "must be of the form \"moduleKey:contentKey\"")
        @Pattern(regexp = Keys.REF_RE, message = "must be of the form \"moduleKey:contentKey\"")
        String ref) {}
