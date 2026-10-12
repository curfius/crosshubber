package com.crosshubber.talenthub.docs;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Documents REST (P1.2): upload → ref (recruiter+), fetch by ref (all staffing roles). Upload
 * destinations are module-owned local storage; external stores arrive via the P4 registry.
 */
@RestController
public class DocumentController {

  private final DocumentService documentService;

  public DocumentController(DocumentService documentService) {
    this.documentService = documentService;
  }

  @PostMapping("/api/documents")
  public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
    ModuleRoles.requireRecruiter();
    DocumentService.StoredDocument stored = documentService.store(file);
    return ResponseEntity.status(201)
        .body(
            Map.of(
                "ref",
                stored.ref(),
                "name",
                stored.name(),
                "mimeType",
                stored.mimeType(),
                "size",
                stored.size()));
  }

  @GetMapping("/api/documents/{ref}")
  public ResponseEntity<?> get(@PathVariable String ref) {
    ModuleRoles.requireReader();
    try {
      DocumentSource.DocumentContent content = documentService.resolve(ref);
      if (content == null) {
        throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "document not found: " + ref);
      }
      return ResponseEntity.ok(
          Map.of(
              "name",
              content.name(),
              "mimeType",
              content.mimeType(),
              "size",
              content.size(),
              "text",
              content.text()));
    } catch (org.springframework.web.server.ResponseStatusException e) {
      throw e;
    } catch (IOException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "document read failed");
    }
  }
}
