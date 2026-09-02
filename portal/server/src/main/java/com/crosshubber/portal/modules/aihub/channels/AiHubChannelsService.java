package com.crosshubber.portal.modules.aihub.channels;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.crosshubber.portal.security.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Channel store — mirrors {@code portal/src/modules/ai-hub/channels.repository.ts}: credentials
 * encrypted (AES-GCM), config merges (JSONB ||), status shallow-merge with null-clearing.
 */
@Service
public class AiHubChannelsService {

  private final AiHubChannelRepository channelRepo;
  private final AiHubChannelInboundRepository inboundRepo;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;

  @PersistenceContext private EntityManager em;

  public AiHubChannelsService(
      AiHubChannelRepository channelRepo,
      AiHubChannelInboundRepository inboundRepo,
      CryptoService cryptoService,
      ObjectMapper objectMapper) {
    this.channelRepo = channelRepo;
    this.inboundRepo = inboundRepo;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
  }

  // ── Queries ──────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listPublic() {
    return channelRepo.findAllByOrderByCreatedAtAsc().stream()
        .map(this::toPublic)
        .toList();
  }

  @Transactional(readOnly = true)
  public AiHubChannelEntity get(String id) {
    return channelRepo.findById(id).orElse(null);
  }

  /** Decrypts the credential blob; null when missing/corrupt. */
  @Transactional(readOnly = true)
  public ChannelCredentials getCredentials(AiHubChannelEntity channel) {
    if (channel.getCredentialsEncrypted() == null) {
      return null;
    }
    Map<String, Object> creds =
        cryptoService.decryptJson(channel.getCredentialsEncrypted(), Map.class);
    return creds != null ? ChannelCredentials.from(creds) : null;
  }

  /** Ids of enabled telegram channels in polling mode (boot scan for pollers). */
  @Transactional(readOnly = true)
  public List<String> listPollingTelegramIds() {
    return channelRepo.findByEnabledTrueAndTypeAndDeliveryMode("telegram", "polling").stream()
        .map(AiHubChannelEntity::getId)
        .toList();
  }

  // ── Commands ─────────────────────────────────────────────────────────

  @Transactional
  public AiHubChannelEntity create(
      String type,
      String name,
      ChannelCredentials credentials,
      Map<String, Object> meta,
      Map<String, Object> config) {
    AiHubChannelEntity channel = new AiHubChannelEntity();
    channel.setId("ch_" + UUID.randomUUID().toString().substring(0, 8));
    channel.setType(type);
    channel.setName(name);
    channel.setEnabled(false);
    channel.setCredentialsEncrypted(cryptoService.encryptJson(credentials.asMap()));
    channel.setCredentialsMeta(writeJson(meta == null ? Map.of() : meta));
    channel.setConfig(writeJson(config == null ? Map.of() : config));
    channel.setStatus("{}");
    channel.setDeliveryMode("webhook");
    return channelRepo.save(channel);
  }

  /**
   * Partial update — config is merged (not replaced) so partial config updates keep other keys;
   * meta is replaced; credentials re-encrypted.
   */
  @Transactional
  public AiHubChannelEntity update(
      String id,
      String name,
      Boolean enabled,
      String deliveryMode,
      ChannelCredentials credentials,
      Map<String, Object> meta,
      Map<String, Object> config) {
    AiHubChannelEntity channel =
        channelRepo
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found"));
    if (name != null) {
      channel.setName(name);
    }
    if (enabled != null) {
      channel.setEnabled(enabled);
    }
    if (deliveryMode != null) {
      channel.setDeliveryMode(deliveryMode);
    }
    if (credentials != null) {
      channel.setCredentialsEncrypted(cryptoService.encryptJson(credentials.asMap()));
    }
    if (meta != null) {
      channel.setCredentialsMeta(writeJson(meta));
    }
    if (config != null) {
      channel.setConfig(writeJson(mergeJson(channel.getConfig(), config)));
    }
    return channelRepo.save(channel);
  }

  @Transactional
  public boolean remove(String id) {
    if (!channelRepo.existsById(id)) {
      return false;
    }
    channelRepo.deleteById(id);
    return true;
  }

  /**
   * Shallow-merges a patch into the channel status JSONB. A {@code null} value clears the key
   * (mirrors the Node JSONB merge + null semantics).
   */
  @Transactional
  public void updateStatus(String id, Map<String, Object> patch) {
    AiHubChannelEntity channel = channelRepo.findById(id).orElse(null);
    if (channel == null) {
      return;
    }
    Map<String, Object> status = parseJson(channel.getStatus());
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if (entry.getValue() == null) {
        status.remove(entry.getKey());
      } else {
        status.put(entry.getKey(), entry.getValue());
      }
    }
    channel.setStatus(writeJson(status));
    channelRepo.save(channel);
  }

  /**
   * Webhook dedupe: returns true when the external message id was seen for the first time (false =
   * platform redelivery, skip it).
   */
  @Transactional
  public boolean markSeen(String channelId, String externalMessageId) {
    if (inboundRepo.existsByChannelIdAndExternalMessageId(channelId, externalMessageId)) {
      return false;
    }
    AiHubChannelInboundEntity seen = new AiHubChannelInboundEntity();
    seen.setChannelId(channelId);
    seen.setExternalMessageId(externalMessageId);
    inboundRepo.save(seen);
    return true;
  }

  // ── DTO ──────────────────────────────────────────────────────────────

  /** Public channel shape — never contains credentials (mirrors toPublic). */
  public Map<String, Object> toPublic(AiHubChannelEntity c) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", c.getId());
    out.put("type", c.getType());
    out.put("name", c.getName());
    out.put("enabled", c.getEnabled());
    out.put("deliveryMode", c.getDeliveryMode() != null ? c.getDeliveryMode() : "webhook");
    out.put("meta", parseStatic(c.getCredentialsMeta(), objectMapper));
    out.put("config", parseStatic(c.getConfig(), objectMapper));
    out.put("status", parseStatic(c.getStatus(), objectMapper));
    out.put("createdAt", c.getCreatedAt().toString());
    out.put("updatedAt", c.getUpdatedAt().toString());
    return out;
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private Map<String, Object> mergeJson(String currentJson, Map<String, Object> patch) {
    Map<String, Object> merged = parseJson(currentJson);
    merged.putAll(patch);
    return merged;
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private Map<String, Object> parseStatic(String raw, ObjectMapper objectMapper) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      JsonNode node = objectMapper.readTree(raw);
      Map<String, Object> out = new LinkedHashMap<>();
      node.properties().forEach(entry -> out.put(entry.getKey(), valueOf(entry.getValue())));
      return out;
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private static Object valueOf(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isArray()) {
      List<Object> list = new java.util.ArrayList<>();
      node.forEach(item -> list.add(valueOf(item)));
      return list;
    }
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      node.properties().forEach(entry -> map.put(entry.getKey(), valueOf(entry.getValue())));
      return map;
    }
    return node.isNull() ? null : node.toString();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("channel serialization failed", e);
    }
  }
}
