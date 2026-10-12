package com.crosshubber.talenthub.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.domain.ClientService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.security.AgentPrincipal;

/** Clients REST: role gates (read all, write manager+admin), shapes, 404/400. */
class ClientControllerTest {

  private ClientService clientService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    clientService = mock(ClientService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ClientController(clientService))
            .setControllerAdvice(new com.crosshubber.talenthub.web.GlobalExceptionHandler())
            .build();
    asUser("staffing-manager");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asUser(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev User", List.of(roles));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                p,
                null,
                java.util.Arrays.stream(roles)
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList()));
  }

  private static ClientService.ClientDto sample() {
    return new ClientService.ClientDto(
        UUID.randomUUID(), "Acme", null, null, List.of(), null, Instant.now().toString());
  }

  @Test
  void readAllowedForStaffingUser() throws Exception {
    asUser("staffing-user");
    when(clientService.list()).thenReturn(List.of(sample()));

    mockMvc
        .perform(get("/api/clients"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clients[0].name").value("Acme"));
  }

  @Test
  void writeRequiresManagerOrAdmin() throws Exception {
    asUser("staffing-recruiter");

    mockMvc
        .perform(
            post("/api/clients").contentType("application/json").content("{\"name\":\"Acme\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("staffing-manager or staffing-admin role required"));
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    when(clientService.create(any())).thenReturn(sample());

    mockMvc
        .perform(
            post("/api/clients").contentType("application/json").content("{\"name\":\"Acme\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  void getUnknownSurfaces404Envelope() throws Exception {
    when(clientService.detail(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "client not found"));

    mockMvc
        .perform(get("/api/clients/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("client not found"));
  }

  @Test
  void getReturnsCardWithRfpHistory() throws Exception {
    RfpDtos.RfpDto rfp =
        new RfpDtos.RfpDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Java squad",
            "rfp",
            "sourcing",
            "tm",
            "manual",
            null,
            null,
            java.util.Map.of(),
            null,
            java.util.List.of(),
            "u1",
            "2026-10-12T00:00:00Z",
            "2026-10-12T00:00:00Z");
    ClientService.ClientDetailDto detail =
        new ClientService.ClientDetailDto(sample(), java.util.List.of(rfp), true);
    when(clientService.detail(any())).thenReturn(detail);

    mockMvc
        .perform(get("/api/clients/" + UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.client.name").value("Acme"))
        .andExpect(jsonPath("$.rfps[0].title").value("Java squad"))
        .andExpect(jsonPath("$.active").value(true));
  }
}
