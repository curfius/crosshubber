package com.crosshubber.portal.modules.registry.manifest;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Module manifest digest — sha256 over Node's digest JSON, {@code JSON.stringify(manifest,
 * Object.keys(manifest).sort())}. The replacer array allow-lists the manifest's top-level key names
 * at EVERY depth; V8 emits keys in replacer-array order (sorted), so nested objects not containing
 * those exact key names collapse to empty objects.
 */
public final class ManifestDigest {

  private ManifestDigest() {}

  public static String sha256(JsonNode manifest, ObjectMapper objectMapper) {
    List<String> allow = new ArrayList<>();
    allow.addAll(manifest.propertyNames());
    Collections.sort(allow);
    try {
      Object filtered = filterKeys(manifest, allow);
      byte[] bytes = objectMapper.writeValueAsBytes(filtered);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("digest failed", e);
    }
  }

  private static Object filterKeys(JsonNode node, List<String> allow) {
    if (node.isObject()) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (String name : allow) {
        if (node.has(name)) {
          out.put(name, filterKeys(node.get(name), allow));
        }
      }
      return out;
    }
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode item : node) {
        out.add(filterKeys(item, allow));
      }
      return out;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    return node.isNull() ? null : node.toString();
  }
}
