package com.crosshubber.clienthub.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.clienthub.domain.ContactDtos.ContactDto;
import com.crosshubber.clienthub.domain.ContactDtos.CreateRequest;
import com.crosshubber.clienthub.domain.ContactDtos.UpdateRequest;
import com.crosshubber.clienthub.domain.ContactService;
import com.crosshubber.clienthub.security.AgentPrincipals;
import com.crosshubber.clienthub.security.ModuleRoles;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

  private final ContactService service;

  public ContactController(ContactService service) {
    this.service = service;
  }

  @GetMapping
  public Page<ContactDto> list(
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String q,
      @PageableDefault(size = 25) Pageable pageable) {
    ModuleRoles.requireReader();
    return service.list(accountId, q, pageable);
  }

  @PostMapping
  public ContactDto create(@Valid @RequestBody CreateRequest req) {
    ModuleRoles.requireEditor();
    return service.create(req, AgentPrincipals.currentName());
  }

  @GetMapping("/{id}")
  public ContactDto detail(@PathVariable UUID id) {
    ModuleRoles.requireReader();
    return service.detail(id);
  }

  @PutMapping("/{id}")
  public ContactDto update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
    ModuleRoles.requireEditor();
    return service.update(id, req, AgentPrincipals.currentName());
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable UUID id) {
    ModuleRoles.requireEditor();
    service.delete(id, AgentPrincipals.currentName());
  }
}
