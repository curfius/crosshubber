package com.crosshubber.portal.modules.i18n.dto;

import java.util.List;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code PUT /api/i18n/labels/{lang}} body. */
public record UpsertLabelsRequest(@NotEmpty @Valid List<@Valid Entry> entries) {

  /** One label entry; keys are dot/dash-segmented lowercase. */
  public record Entry(
      @NotBlank @Pattern(regexp = Keys.I18N_KEY_RE) String key, @NotNull String value) {}
}
