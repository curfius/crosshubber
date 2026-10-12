package com.crosshubber.talenthub.docs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.talenthub.config.TalentHubProperties;
import com.crosshubber.talenthub.security.AgentPrincipal;
import com.crosshubber.talenthub.web.GlobalExceptionHandler;

import tools.jackson.databind.json.JsonMapper;

/** Documents REST: role gates + shapes. */
class DocumentControllerTest {

  private DocumentSource documentSource;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    documentSource = mock(DocumentSource.class);
    DocumentService documentService =
        new DocumentService(
            new DocumentService.DocumentServiceConfig("target/test-documents"),
            documentSource,
            new JsonMapper());
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new DocumentController(documentService, new TalentHubProperties()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    asUser("staffing-recruiter");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asUser(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev User", java.util.List.of(roles));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                p,
                null,
                java.util.Arrays.stream(roles)
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList()));
  }

  @Test
  void recruiterCanUploadAndReadBack() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "spec.txt", "text/plain", "body".getBytes(StandardCharsets.UTF_8));

    String body =
        mockMvc
            .perform(multipart("/api/documents").file(file))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ref").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String ref = new JsonMapper().readTree(body).path("ref").asString();

    mockMvc
        .perform(get("/api/documents/" + ref))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("spec.txt"))
        .andExpect(jsonPath("$.text").value("body"));
  }

  @Test
  void uploadRequiresRecruiterOrBetter() throws Exception {
    asUser("staffing-user");

    mockMvc
        .perform(
            multipart("/api/documents")
                .file(
                    new MockMultipartFile(
                        "file", "x.txt", "text/plain", "b".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.error")
                .value("staffing-recruiter, staffing-manager or staffing-admin role required"));
  }

  @Test
  void unknownExternalRefSurfacesNotFoundEnvelope() throws Exception {
    when(documentSource.fetchDocument("fake:nope")).thenThrow(new java.io.IOException("upstream"));
    asUser("staffing-manager");

    mockMvc
        .perform(get("/api/documents/fake:nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("document not found: fake:nope"));
  }

  @Test
  void malformedUploadRefIsNotFound() throws Exception {
    asUser("staffing-manager");

    mockMvc
        .perform(get("/api/documents/not-a-known-kind:whatever"))
        .andExpect(status().isNotFound());
  }
}
