package com.crosshubber.portal.modules.registry.manifest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.portal.config.GlobalExceptionHandler;
import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/registry/fetch} answers with a stable {@code "code"} next to the message: the
 * registry UI localizes on the code, humans and logs read the message.
 */
class ManifestFetchControllerTest {

  private static final String URL = "http://localhost:28092/";

  private ManifestValidator validator;
  private ManifestFetcher fetcher;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    validator = mock(ManifestValidator.class);
    fetcher = mock(ManifestFetcher.class);
    ManifestController controller =
        new ManifestController(
            mock(InstallService.class),
            validator,
            fetcher,
            new PortalProperties(),
            JsonMapper.builder().build());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void unreachableUpstreamIs502WithCodeAndMessage() throws Exception {
    when(fetcher.fetchManifestFromUrl(anyString()))
        .thenThrow(
            new ManifestFetchException(
                ManifestFetchException.Code.UNREACHABLE, URL, "connection refused", null));

    mvc.perform(
            post("/api/registry/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("unreachable"))
        .andExpect(jsonPath("$.error").value("could not fetch " + URL + ": connection refused"));
  }

  @Test
  void blockedHostIs403NotA502() throws Exception {
    when(fetcher.fetchManifestFromUrl(anyString()))
        .thenThrow(
            new ManifestFetchException(
                ManifestFetchException.Code.BLOCKED, URL, "host is not allowed", null));

    mvc.perform(
            post("/api/registry/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("blocked"));
  }

  @Test
  void unresolvableHostIs502WithCode() throws Exception {
    when(fetcher.fetchManifestFromUrl(anyString()))
        .thenThrow(
            new ManifestFetchException(
                ManifestFetchException.Code.UNKNOWN_HOST, URL, "cannot resolve host", null));

    mvc.perform(
            post("/api/registry/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("unknown-host"));
  }

  @Test
  void invalidUrlIs400WithCode() throws Exception {
    when(fetcher.fetchManifestFromUrl(anyString()))
        .thenThrow(
            new ManifestFetchException(
                ManifestFetchException.Code.INVALID_URL, URL, "not a valid URL", null));

    mvc.perform(
            post("/api/registry/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-url"));
  }

  @Test
  void missingUrlAndBaseUrlIs400WithCode() throws Exception {
    mvc.perform(post("/api/registry/fetch").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
  }

  @Test
  void invalidManifestIs422WithIssuesAndCode() throws Exception {
    when(fetcher.fetchManifestFromUrl(anyString()))
        .thenReturn(JsonMapper.builder().build().createObjectNode());
    when(validator.parse(any()))
        .thenReturn(ManifestValidator.Result.fail(List.of("key: required")));

    mvc.perform(
            post("/api/registry/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("invalid-manifest"))
        .andExpect(jsonPath("$.issues[0]").value("key: required"));
  }
}
