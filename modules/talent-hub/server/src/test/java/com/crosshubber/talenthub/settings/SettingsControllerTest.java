package com.crosshubber.talenthub.settings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.talenthub.security.AgentPrincipal;
import com.crosshubber.talenthub.web.GlobalExceptionHandler;

/** Settings REST: admin role gate, view shape, validation errors. */
class SettingsControllerTest {

  private ModuleSettingsService settingsService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    settingsService = mock(ModuleSettingsService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new SettingsController(settingsService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    asUser("staffing-admin");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asUser(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev Admin", List.of(roles));
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
  void getViewForAdmin() throws Exception {
    when(settingsService.view())
        .thenReturn(
            new ModuleSettingsService.SettingsView(
                new ModuleSettingsService.LlmView("https://llm.example/v1", true, "gpt-x"),
                new ModuleSettingsService.MatchingView("rules", "", false, 5000),
                Map.of(),
                Map.of()));

    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.llm.baseUrl").value("https://llm.example/v1"))
        .andExpect(jsonPath("$.llm.hasApiKey").value(true))
        .andExpect(jsonPath("$.llm.model").value("gpt-x"));
  }

  @Test
  void getRequiresAdminRole() throws Exception {
    asUser("staffing-user");

    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("staffing-admin role required"));
  }

  @Test
  void putRejectsValidationErrors() throws Exception {
    when(settingsService.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn("llm.model: too long (max 120 chars)");

    mockMvc
        .perform(put("/api/settings").contentType("application/json").content("{\"llm\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("llm.model: too long (max 120 chars)"));
  }
}
