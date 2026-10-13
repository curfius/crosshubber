package com.crosshubber.clienthub.web;

import java.util.UUID;

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

import com.crosshubber.clienthub.domain.AccountDtos.AccountDetailDto;
import com.crosshubber.clienthub.domain.AccountDtos.AccountDto;
import com.crosshubber.clienthub.domain.AccountDtos.CreateRequest;
import com.crosshubber.clienthub.domain.AccountDtos.UpdateRequest;
import com.crosshubber.clienthub.domain.AccountService;
import com.crosshubber.clienthub.security.AgentPrincipals;
import com.crosshubber.clienthub.security.ModuleRoles;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @GetMapping
  public org.springframework.data.domain.Page<AccountDto> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @PageableDefault(size = 25) Pageable pageable) {
    ModuleRoles.requireReader();
    return service.list(q, status, pageable);
  }

  @PostMapping
  public AccountDetailDto create(@Valid @RequestBody CreateRequest req) {
    ModuleRoles.requireEditor();
    return service.create(req, AgentPrincipals.currentName());
  }

  @GetMapping("/{id}")
  public AccountDetailDto detail(@PathVariable UUID id) {
    ModuleRoles.requireReader();
    return service.detail(id);
  }

  @PutMapping("/{id}")
  public AccountDetailDto update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
    ModuleRoles.requireEditor();
    return service.update(id, req, AgentPrincipals.currentName());
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable UUID id) {
    ModuleRoles.requireEditor();
    service.delete(id, AgentPrincipals.currentName());
  }
}
