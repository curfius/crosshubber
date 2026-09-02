package com.crosshubber.portal.security;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AES-256-GCM encryption for LLM keys and channel credentials.
 *
 * <p>Mirrors {@code portal/src/modules/ai-hub/crypto.ts}: random 12-byte IV, 16-byte tag,
 * base64(iv+tag+ciphertext).
 */
@Service
public class CryptoService {

  private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
  private static final String ALGO = "AES/GCM/NoPadding";
  private static final int IV_LEN = 12;
  private static final int TAG_LEN_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PortalProperties props;
  private final ObjectMapper objectMapper;
  private byte[] keyBytes;

  public CryptoService(PortalProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
  }

  private byte[] getKey() {
    if (keyBytes != null) {
      return keyBytes;
    }
    String raw = props.getEncryptionKey();
    if (raw == null || raw.length() != 64) {
      throw new IllegalStateException("PORTAL_ENCRYPTION_KEY must be 64 hex chars (32 bytes)");
    }
    keyBytes = hexToBytes(raw);
    log.info("[crypto] encryption key loaded ({} hex chars)", raw.length());
    return keyBytes;
  }

  /** Encrypts plaintext with random IV. */
  public String encryptApiKey(String plaintext) {
    try {
      byte[] iv = new byte[IV_LEN];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(getKey(), "AES"),
          new GCMParameterSpec(TAG_LEN_BITS, iv));
      byte[] encrypted =
          cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      // GCM in Java appends tag to ciphertext; we need to split tag (last 16) for parity
      // Node's format: iv(12) + tag(16) + cipher. Java's encrypted = cipher+tag.
      byte[] tag = new byte[16];
      byte[] cipherBytes = new byte[encrypted.length - 16];
      System.arraycopy(encrypted, 0, cipherBytes, 0, cipherBytes.length);
      System.arraycopy(encrypted, cipherBytes.length, tag, 0, 16);
      byte[] out = new byte[IV_LEN + 16 + cipherBytes.length];
      System.arraycopy(iv, 0, out, 0, IV_LEN);
      System.arraycopy(tag, 0, out, IV_LEN, 16);
      System.arraycopy(cipherBytes, 0, out, IV_LEN + 16, cipherBytes.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      log.error("[crypto] encrypt failed", e);
      throw new RuntimeException("encrypt failed", e);
    }
  }

  /** Decrypts base64(iv+tag+cipher). */
  public String decryptApiKey(String encrypted) {
    try {
      byte[] buf = Base64.getDecoder().decode(encrypted);
      if (buf.length < IV_LEN + 16 + 1) {
        throw new IllegalArgumentException("invalid encrypted value");
      }
      byte[] iv = new byte[IV_LEN];
      byte[] tag = new byte[16];
      byte[] cipherBytes = new byte[buf.length - IV_LEN - 16];
      System.arraycopy(buf, 0, iv, 0, IV_LEN);
      System.arraycopy(buf, IV_LEN, tag, 0, 16);
      System.arraycopy(buf, IV_LEN + 16, cipherBytes, 0, cipherBytes.length);
      // Java expects cipher+tag
      byte[] cipherPlusTag = new byte[cipherBytes.length + 16];
      System.arraycopy(cipherBytes, 0, cipherPlusTag, 0, cipherBytes.length);
      System.arraycopy(tag, 0, cipherPlusTag, cipherBytes.length, 16);
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(getKey(), "AES"),
          new GCMParameterSpec(TAG_LEN_BITS, iv));
      byte[] decrypted = cipher.doFinal(cipherPlusTag);
      return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("[crypto] decrypt failed: {}", e.getMessage());
      throw new RuntimeException("decrypt failed: " + e.getMessage(), e);
    }
  }

  /** Encrypts JSON-serialisable object. */
  public String encryptJson(Object value) {
    try {
      String json = objectMapper.writeValueAsString(value);
      return encryptApiKey(json);
    } catch (Exception e) {
      throw new RuntimeException("encryptJson failed", e);
    }
  }

  /** Decrypts blob produced by encryptJson, returns null on failure. */
  public <T> T decryptJson(String encrypted, Class<T> type) {
    try {
      String json = decryptApiKey(encrypted);
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      log.warn("[crypto] decryptJson failed: {}", e.getMessage());
      return null;
    }
  }

  public String maskApiKey(String plaintext) {
    if (plaintext == null || plaintext.length() <= 8) {
      return "••••••••";
    }
    return plaintext.substring(0, 4) + "•••" + plaintext.substring(plaintext.length() - 4);
  }

  private static byte[] hexToBytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int idx = i * 2;
      out[i] = (byte) Integer.parseInt(hex.substring(idx, idx + 2), 16);
    }
    return out;
  }
}
