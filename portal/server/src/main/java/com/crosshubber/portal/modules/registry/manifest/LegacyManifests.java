package com.crosshubber.portal.modules.registry.manifest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Idempotent upgrade of manifests stored under the pre-2026-10 layout: top-level {@code content} →
 * {@code shell}, {@code agentContributions} → {@code agentic}; {@code capabilities}, {@code
 * userSettingsSchema}, {@code adminSettingsSchema}, and entry-level {@code sortOrder} are dropped.
 * Used by the Reconciler at boot so previously installed versions (including drafts and rollback
 * targets) keep validating under the baseline schema — see {@link ManifestValidator}.
 */
public final class LegacyManifests {

  private LegacyManifests() {}

  /** True when the manifest carries any pre-2026-10 top-level key. */
  public static boolean isLegacy(JsonNode manifest) {
    if (manifest == null || !manifest.isObject()) {
      return false;
    }
    return manifest.has("content")
        || manifest.has("agentContributions")
        || manifest.has("capabilities")
        || manifest.has("userSettingsSchema")
        || manifest.has("adminSettingsSchema");
  }

  /**
   * Returns an upgraded deep copy. Content/agent blocks are renamed when present (a conflict with
   * the modern key would mean a corrupt row and throws); removed keys are stripped; entry-level
   * {@code sortOrder} (never consumed by install) is stripped from shell groups.
   */
  public static JsonNode upgrade(JsonNode raw) {
    ObjectNode manifest = ((ObjectNode) raw.deepCopy());

    if (manifest.has("shell") && manifest.has("content")) {
      throw new IllegalStateException("corrupt stored manifest: legacy and modern shell block");
    }
    if (manifest.has("content")) {
      if (!manifest.get("content").isObject()) {
        throw new IllegalStateException(
            "corrupt stored manifest: legacy content block not an object");
      }
      renameBlock(manifest, "content", "shell");
    }
    if (manifest.has("agentic") && manifest.has("agentContributions")) {
      throw new IllegalStateException("corrupt stored manifest: legacy and modern agentic block");
    }
    if (manifest.has("agentContributions")) {
      if (!manifest.get("agentContributions").isObject()) {
        throw new IllegalStateException(
            "corrupt stored manifest: legacy agentContributions block not an object");
      }
      renameBlock(manifest, "agentContributions", "agentic");
    }

    manifest.remove("capabilities");
    manifest.remove("userSettingsSchema");
    manifest.remove("adminSettingsSchema");

    JsonNode shell = manifest.get("shell");
    if (shell != null && shell.isObject()) {
      for (String group :
          new String[] {"applications", "features", "adminSettings", "userSettings"}) {
        JsonNode entries = shell.path(group);
        if (entries.isArray()) {
          for (JsonNode entry : entries) {
            if (entry.isObject()) {
              ((ObjectNode) entry).remove("sortOrder");
            }
          }
        }
      }
    }
    return manifest;
  }

  private static void renameBlock(ObjectNode manifest, String from, String to) {
    JsonNode block = manifest.get(from);
    manifest.remove(from);
    manifest.set(to, block);
  }
}
