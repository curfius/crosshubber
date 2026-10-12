package com.crosshubber.talenthub.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document storage (P1.2): uploads live in the module volume dir (module-owned local storage);
 * fetching by ref serves uploaded files or delegates to the {@link DocumentSource} port for
 * external/fixture refs (fake corpus in emulated mode). Every docsource kind value must have a bean
 * — the known conditional-bean gotcha (P4 registry resolves selection at runtime).
 */
@Service
public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  public record StoredDocument(String ref, String name, String mimeType, long size) {}

  /** Config record so the dir stays injectable/testable (tmp dirs in tests). */
  public record DocumentServiceConfig(String dir) {}

  private final Path documentsDir;
  private final DocumentSource documentSource;
  private final ObjectMapper objectMapper;

  public DocumentService(
      DocumentServiceConfig config, DocumentSource documentSource, ObjectMapper objectMapper) {
    this.documentsDir = Path.of(config.dir());
    this.documentSource = documentSource;
    this.objectMapper = objectMapper;
  }

  /** Stores an uploaded file; ref = {@code upload:<uuid>} (uuid.bin + uuid.json sidecar). */
  public StoredDocument store(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file is required");
    }
    Files.createDirectories(documentsDir);
    String id = UUID.randomUUID().toString();
    String raw = file.getOriginalFilename();
    String name = sanitize(raw == null || raw.isBlank() ? "document" : raw);
    String mimeType =
        file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    Files.write(documentsDir.resolve(id + ".bin"), file.getBytes());
    ObjectNode meta = objectMapper.createObjectNode();
    meta.put("name", name);
    meta.put("mimeType", mimeType);
    Files.write(
        documentsDir.resolve(id + ".json"),
        objectMapper.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8));
    log.info("[documents] stored {} ({} bytes)", name, file.getSize());
    return new StoredDocument("upload:" + id, name, mimeType, file.getSize());
  }

  /** Fetches document content by ref: uploads from the volume, other kinds via the port. */
  public DocumentSource.DocumentContent resolve(String ref) throws IOException {
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("ref is required");
    }
    if (ref.startsWith("upload:")) {
      return readUploaded(ref);
    }
    try {
      return documentSource.fetchDocument(ref);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.warn("[documents] fetch of {} failed: {}", ref, e.getMessage());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found: " + ref);
    }
  }

  private DocumentSource.DocumentContent readUploaded(String ref) throws IOException {
    String id = ref.substring("upload:".length());
    Path data = documentsDir.resolve(id + ".bin");
    Path meta = documentsDir.resolve(id + ".json");
    if (!Files.isRegularFile(data) || !Files.isRegularFile(meta)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found: " + ref);
    }
    String json = Files.readString(meta, StandardCharsets.UTF_8);
    tools.jackson.databind.JsonNode node = objectMapper.readTree(json);
    byte[] bytes = Files.readAllBytes(data);
    return new DocumentSource.DocumentContent(
        node.path("name").asString("document"),
        node.path("mimeType").asString("application/octet-stream"),
        bytes.length,
        new String(bytes, StandardCharsets.UTF_8));
  }

  static String sanitize(String name) {
    return name.replaceAll("[\\\\/:*?\"<>|\r\n]", "_");
  }
}
