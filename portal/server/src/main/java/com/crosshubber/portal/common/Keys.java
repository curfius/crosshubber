package com.crosshubber.portal.common;

import java.util.List;
import java.util.regex.Pattern;

/** Shared validation patterns and fixed vocabularies used across modules. */
public final class Keys {

  /** Kebab-case identifier (module keys, entry keys, group keys, scopes, label namespaces). */
  public static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";

  /** Language code: 2-3 letters, optional hyphen-separated subtags. Case-insensitive. */
  public static final String LANG_CODE_RE = "(?i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

  /** Reference format {@code moduleKey:contentKey}, both sides kebab-case. */
  public static final String REF_RE = "^[a-z0-9][a-z0-9-]{0,63}:[a-z0-9][a-z0-9-]{0,63}$";

  /** I18n label key: dot- or dash-separated lowercase segments ({@code app.section.label}). */
  public static final String I18N_KEY_RE = "^[a-z0-9]+(?:[.-][a-z0-9]+)+$";

  /** Web component (mfe element) name. */
  public static final String ELEMENT_RE = "^[a-z][a-z0-9-]*$";

  /**
   * Agent contribution name (manifest {@code agentContributions.tools[]/skills[]/agents[]}). Snake
   * case is allowed because these names surface to LLM providers as function names, where
   * underscores are the norm (OpenAI: {@code ^[a-zA-Z0-9_-]{1,64}$}).
   */
  public static final String AGENT_NAME_RE = "^[a-z][a-z0-9_-]*$";

  /** Client-supplied UUIDs for pinned-tree nodes. */
  public static final Pattern UUID_PATTERN =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
          Pattern.CASE_INSENSITIVE);

  /**
   * Module content types. Categories live on the {@code ModuleContentCategory} enum (value
   * authority).
   */
  public static final List<String> TYPES = List.of("iframe", "embedded", "mfe", "link");

  private Keys() {}
}
