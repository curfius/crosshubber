package com.crosshubber.solutions.settings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.solutions.security.AgentPrincipal;

/** Settings REST: admin role gate, masked view, validation errors. */
class SettingsControllerTest {

  private ModuleSettingsService settingsService;
  private MockMvc mockMvc;
  private ModuleSettingsService.SettingsView view;

  @BeforeEach
  void setUp() {
    settingsService = mock(ModuleSettingsService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new SettingsController(settingsService))
            .setControllerAdvice(new com.crosshubber.solutions.web.GlobalExceptionHandler())
            .build();
    view =
        new ModuleSettingsService.SettingsView(
            new ModuleSettingsService.AgentSettingsView(true, "p", "http://x/v1", true, "m"),
            java.util.Map.of("list_projects", true),
            new ModuleSettingsService.RagSettings(3, 2000));
    asUser("solutions-admin");
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
    when(settingsService.view()).thenReturn(view);

    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent.enabled").value(true))
        .andExpect(jsonPath("$.agent.hasApiKey").value(true))
        .andExpect(jsonPath("$.tools.list_projects").value(true))
        .andExpect(jsonPath("$.rag.maxDocsPerQuery").value(3));
  }

  @Test
  void getRequiresAdminRole() throws Exception {
    asUser("solutions-user");

    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("solutions-admin role required"));
  }

  @Test
  void putValidatesAndSaves() throws Exception {
    when(settingsService.validate(org.mockito.ArgumentMatchers.any())).thenReturn(null);
    when(settingsService.update(org.mockito.ArgumentMatchers.any())).thenReturn(view);

    mockMvc
        .perform(
            put("/api/settings")
                .contentType("application/json")
                .content("{\"tools\":{\"list_projects\":false}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent.enabled").value(true));
  }

  @Test
  void putRejectsValidationErrors() throws Exception {
    when(settingsService.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn("rag.maxDocsPerQuery: must be 1-20");

    mockMvc
        .perform(put("/api/settings").contentType("application/json").content("{\"rag\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("rag.maxDocsPerQuery: must be 1-20"));
  }
}
