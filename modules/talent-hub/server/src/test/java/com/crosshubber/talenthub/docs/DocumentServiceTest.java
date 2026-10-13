package com.crosshubber.talenthub.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

/** DocumentService: store/resolve round-trip, port delegation, 404s. */
class DocumentServiceTest {

  @TempDir Path tempDir;

  private DocumentService service;
  private DocumentSource documentSource;

  @BeforeEach
  void setUp() {
    documentSource = mock(DocumentSource.class);
    service =
        new DocumentService(
            new DocumentService.DocumentServiceConfig(tempDir.toString()),
            documentSource,
            new JsonMapper());
  }

  @Test
  void storeThenResolveRoundTrips() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "rfp-spec.txt",
            "text/plain",
            "Acme needs a Java squad.".getBytes(StandardCharsets.UTF_8));

    DocumentService.StoredDocument stored = service.store(file);
    DocumentSource.DocumentContent resolved = service.resolve(stored.ref());

    assertThat(stored.ref()).startsWith("upload:");
    assertThat(resolved.name()).isEqualTo("rfp-spec.txt");
    assertThat(resolved.mimeType()).isEqualTo("text/plain");
    assertThat(resolved.text()).isEqualTo("Acme needs a Java squad.");
    assertThat(Files.list(tempDir).count()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void emptyUploadIsRejected() {
    MockMultipartFile empty = new MockMultipartFile("file", "x.txt", "text/plain", new byte[0]);

    assertThatThrownBy(() -> service.store(empty)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.store(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonUploadRefsDelegateToThePort() throws Exception {
    when(documentSource.fetchDocument("fake:cv-ana"))
        .thenReturn(new DocumentSource.DocumentContent("cv-ana", "text/plain", 4, "text"));

    DocumentSource.DocumentContent resolved = service.resolve("fake:cv-ana");

    assertThat(resolved.name()).isEqualTo("cv-ana");
  }

  @Test
  void portFailureSurfacesAsNotFound() throws Exception {
    when(documentSource.fetchDocument("fake:nope")).thenThrow(new IOException("upstream"));

    assertThatThrownBy(() -> service.resolve("fake:nope"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void unknownUploadRefIsNotFound() {
    assertThatThrownBy(() -> service.resolve("upload:00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void metadataReadsSidecarWithoutContent() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "rfp-spec.txt", "text/plain", "body".getBytes(StandardCharsets.UTF_8));

    DocumentService.StoredDocument stored = service.store(file);
    DocumentService.DocMeta meta = service.metadata(stored.ref());

    assertThat(meta).isNotNull();
    assertThat(meta.name()).isEqualTo("rfp-spec.txt");
    assertThat(meta.mimeType()).isEqualTo("text/plain");
    assertThat(meta.size()).isNull();
  }

  @Test
  void metadataIsNullForUnknownAndExternalRefs() {
    assertThat(service.metadata("upload:00000000-0000-0000-0000-000000000000")).isNull();
    assertThat(service.metadata("fake:cv-ana")).isNull();
    assertThat(service.metadata(null)).isNull();
    assertThat(service.metadata("")).isNull();
  }
}
