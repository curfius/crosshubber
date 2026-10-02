package com.crosshubber.solutions.settings;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.solutions.config.SolutionsProperties;

/**
 * AES-256-GCM encryption for module-side credentials (same layout as the portal's CryptoService:
 * base64(12-byte IV + 16-byte tag + ciphertext)). Key comes from module config (env), dev default
 * insecure-by-design.
 */
@Service
public class CryptoService {

  private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
  private static final String ALGO = "AES/GCM/NoPadding";
  private static final int IV_LEN = 12;
  private static final int TAG_LEN = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SolutionsProperties props;
  private volatile byte[] keyBytes;

  public CryptoService(SolutionsProperties props) {
    this.props = props;
  }

  private byte[] getKey() {
    byte[] local = keyBytes;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      if (keyBytes == null) {
        String raw = props.getCryptoKey();
        if (raw == null || raw.length() != 64) {
          throw new IllegalStateException("SOLUTIONS_CRYPTO_KEY must be 64 hex chars (32 bytes)");
        }
        keyBytes = hexToBytes(raw);
        log.info("[crypto] encryption key loaded ({} hex chars)", raw.length());
      }
      return keyBytes;
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_LEN];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(getKey(), "AES"),
          new GCMParameterSpec(TAG_LEN * 8, iv));
      byte[] encrypted =
          cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] tag = new byte[TAG_LEN];
      byte[] cipherBytes = new byte[encrypted.length - TAG_LEN];
      System.arraycopy(encrypted, 0, cipherBytes, 0, cipherBytes.length);
      System.arraycopy(encrypted, cipherBytes.length, tag, 0, TAG_LEN);
      byte[] out = new byte[IV_LEN + TAG_LEN + cipherBytes.length];
      System.arraycopy(iv, 0, out, 0, IV_LEN);
      System.arraycopy(tag, 0, out, IV_LEN, TAG_LEN);
      System.arraycopy(cipherBytes, 0, out, IV_LEN + TAG_LEN, cipherBytes.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      log.error("[crypto] encrypt failed", e);
      throw new RuntimeException("encrypt failed", e);
    }
  }

  public String decrypt(String encrypted) {
    try {
      byte[] buf = Base64.getDecoder().decode(encrypted);
      if (buf.length < IV_LEN + TAG_LEN + 1) {
        throw new IllegalArgumentException("invalid encrypted value");
      }
      byte[] iv = new byte[IV_LEN];
      byte[] tag = new byte[TAG_LEN];
      byte[] cipherBytes = new byte[buf.length - IV_LEN - TAG_LEN];
      System.arraycopy(buf, 0, iv, 0, IV_LEN);
      System.arraycopy(buf, IV_LEN, tag, 0, TAG_LEN);
      System.arraycopy(buf, IV_LEN + TAG_LEN, cipherBytes, 0, cipherBytes.length);
      byte[] cipherPlusTag = new byte[cipherBytes.length + TAG_LEN];
      System.arraycopy(cipherBytes, 0, cipherPlusTag, 0, cipherBytes.length);
      System.arraycopy(tag, 0, cipherPlusTag, cipherBytes.length, TAG_LEN);
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(getKey(), "AES"),
          new GCMParameterSpec(TAG_LEN * 8, iv));
      return new String(cipher.doFinal(cipherPlusTag), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("[crypto] decrypt failed: {}", e.getMessage());
      throw new RuntimeException("decrypt failed: " + e.getMessage(), e);
    }
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
