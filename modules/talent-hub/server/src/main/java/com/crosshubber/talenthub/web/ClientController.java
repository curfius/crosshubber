package com.crosshubber.talenthub.web;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.ClientService;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Clients REST (P1.1): read for all staffing roles; writes for manager+admin. Role gates are
 * server-side (ModuleRoles); the UI only hides.
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

  private final ClientService clientService;

  public ClientController(ClientService clientService) {
    this.clientService = clientService;
  }

  @GetMapping
  public Map<String, Object> list() {
    ModuleRoles.requireReader();
    return Map.of("clients", clientService.list());
  }

  @GetMapping("/{id}")
  public ClientService.ClientDetailDto get(@PathVariable UUID id) {
    ModuleRoles.requireReader();
    return clientService.detail(id);
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestBody ClientService.ClientCreateRequest body) {
    ModuleRoles.requireClientWriter();
    ClientService.ClientDto created = clientService.create(body);
    return ResponseEntity.created(URI.create("/api/clients/" + created.id())).body(created);
  }
}
