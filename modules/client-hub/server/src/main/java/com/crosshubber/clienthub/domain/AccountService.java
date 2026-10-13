package com.crosshubber.clienthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.clienthub.domain.AccountDtos.AccountDetailDto;
import com.crosshubber.clienthub.domain.AccountDtos.AccountDto;
import com.crosshubber.clienthub.domain.AccountDtos.ContactSummaryDto;
import com.crosshubber.clienthub.domain.AccountDtos.CreateRequest;
import com.crosshubber.clienthub.domain.AccountDtos.UpdateRequest;
import com.crosshubber.clienthub.events.PortalEventPublisher;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Account CRUD + search service. */
@Service
public class AccountService {

  private final AccountRepository repository;
  private final ContactRepository contactRepository;
  private final PortalEventPublisher eventPublisher;

  public AccountService(
      AccountRepository repository,
      ContactRepository contactRepository,
      PortalEventPublisher eventPublisher) {
    this.repository = repository;
    this.contactRepository = contactRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional(readOnly = true)
  public Page<AccountDto> list(String query, String status, Pageable pageable) {
    if (query != null && query.isBlank()) {
      query = null;
    }
    if (status != null && status.isBlank()) {
      status = null;
    }
    Page<AccountEntity> page =
        status == null
            ? repository.search(query, pageable)
            : repository.searchByStatus(status, query, pageable);
    return page.map(this::toDto);
  }

  @Transactional(readOnly = true)
  public AccountDetailDto detail(UUID id) {
    AccountEntity account = getOrThrow(id);
    List<ContactSummaryDto> contacts =
        contactRepository.findByAccountIdOrderById(id).stream().map(this::toContactSummary).toList();
    return toDetailDto(account, contacts);
  }

  @Transactional
  public AccountDetailDto create(CreateRequest req, String actorName) {
    if (repository.existsByKey(req.key())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "an account with key '" + req.key() + "' already exists");
    }
    AccountEntity account = new AccountEntity();
    account.setId(UUID.randomUUID());
    applyCreateRequest(account, req);
    account.setCreatedAt(Instant.now());
    account.setUpdatedAt(Instant.now());
    AccountEntity saved = repository.save(account);
    List<ContactSummaryDto> contacts = List.of();
    publish("account.created", "Account created", saved.getName(), "info", "/accounts/" + saved.getId());
    return toDetailDto(saved, contacts);
  }

  @Transactional
  public AccountDetailDto update(UUID id, UpdateRequest req, String actorName) {
    AccountEntity account = getOrThrow(id);
    applyUpdateRequest(account, req);
    account.setUpdatedAt(Instant.now());
    AccountEntity saved = repository.save(account);
    List<ContactSummaryDto> contacts =
        contactRepository.findByAccountIdOrderById(id).stream().map(this::toContactSummary).toList();
    publish("account.updated", "Account updated", saved.getName(), "info", "/accounts/" + saved.getId());
    return toDetailDto(saved, contacts);
  }

  @Transactional
  public void delete(UUID id, String actorName) {
    AccountEntity account = getOrThrow(id);
    repository.delete(account);
    publish("account.deleted", "Account deleted", account.getName(), "warning", null);
  }

  @Transactional(readOnly = true)
  public ObjectNode agentListAccounts(String query, String status, int limit) {
    Pageable pageable = Pageable.ofSize(Math.min(limit, 50)).withPage(0);
    if (query != null && query.isBlank()) {
      query = null;
    }
    if (status != null && status.isBlank()) {
      status = null;
    }
    Page<AccountEntity> page =
        status == null
            ? repository.search(query, pageable)
            : repository.searchByStatus(status, query, pageable);
    var node = new ObjectMapper().createObjectNode();
    var accounts = node.putArray("accounts");
    for (AccountEntity a : page.getContent()) {
      accounts.add(toAgentNode(a));
    }
    node.put("total", page.getTotalElements());
    return node;
  }

  @Transactional(readOnly = true)
  public ObjectNode agentGetAccount(UUID accountId) {
    AccountEntity account = getOrThrow(accountId);
    var node = new ObjectMapper().createObjectNode();
    node.set("account", toAgentNode(account));
    var contacts = node.putArray("contacts");
    for (ContactEntity c : contactRepository.findByAccountIdOrderById(accountId)) {
      contacts.add(contactToNode(c));
    }
    return node;
  }

  @Transactional(readOnly = true)
  public ObjectNode agentSuggestNextAction(UUID accountId) {
    AccountEntity account = getOrThrow(accountId);
    var node = new ObjectMapper().createObjectNode();
    node.put("accountId", accountId.toString());
    node.put("suggestion", suggestAction(account));
    node.put("rationale", "Based on account status '" + account.getStatus() + "' and last update.");
    node.put("priority", "medium");
    return node;
  }

  private String suggestAction(AccountEntity account) {
    if ("prospect".equals(account.getStatus())) {
      return "Schedule a discovery call to qualify the prospect.";
    }
    if ("active".equals(account.getStatus())) {
      return "Check in with the account owner for a quarterly review.";
    }
    if ("inactive".equals(account.getStatus())) {
      return "Send a re-engagement email to renew the relationship.";
    }
    return "Review the account details and plan next steps.";
  }

  private AccountEntity getOrThrow(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
  }

  private void applyCreateRequest(AccountEntity account, CreateRequest req) {
    account.setKey(req.key());
    account.setName(req.name());
    account.setType(req.type() != null ? req.type() : "client");
    account.setIndustry(req.industry());
    account.setWebsite(req.website());
    account.setPhone(req.phone());
    account.setEmail(req.email());
    account.setAddress(req.address() != null ? req.address() : java.util.Map.of());
    account.setOwner(req.owner());
    account.setTags(req.tags() != null ? req.tags() : List.of());
    account.setStatus(req.status() != null ? req.status() : "active");
    account.setNotes(req.notes());
    account.setExternalRefs(req.externalRefs() != null ? req.externalRefs() : java.util.Map.of());
  }

  private void applyUpdateRequest(AccountEntity account, UpdateRequest req) {
    account.setName(req.name());
    if (req.type() != null) account.setType(req.type());
    if (req.industry() != null) account.setIndustry(req.industry());
    if (req.website() != null) account.setWebsite(req.website());
    if (req.phone() != null) account.setPhone(req.phone());
    if (req.email() != null) account.setEmail(req.email());
    if (req.address() != null) account.setAddress(req.address());
    if (req.owner() != null) account.setOwner(req.owner());
    if (req.tags() != null) account.setTags(req.tags());
    if (req.status() != null) account.setStatus(req.status());
    if (req.notes() != null) account.setNotes(req.notes());
    if (req.externalRefs() != null) account.setExternalRefs(req.externalRefs());
  }

  private AccountDto toDto(AccountEntity a) {
    long contactCount = repository.countContacts(a.getId());
    return new AccountDto(
        a.getId(),
        a.getKey(),
        a.getName(),
        a.getType(),
        a.getIndustry(),
        a.getStatus(),
        a.getOwner(),
        contactCount,
        a.getUpdatedAt());
  }

  private AccountDetailDto toDetailDto(AccountEntity a, List<ContactSummaryDto> contacts) {
    return new AccountDetailDto(
        a.getId(),
        a.getKey(),
        a.getName(),
        a.getType(),
        a.getIndustry(),
        a.getWebsite(),
        a.getPhone(),
        a.getEmail(),
        a.getAddress(),
        a.getOwner(),
        new ArrayList<>(a.getTags()),
        a.getStatus(),
        a.getNotes(),
        a.getExternalRefs(),
        contacts,
        a.getCreatedAt(),
        a.getUpdatedAt());
  }

  private ContactSummaryDto toContactSummary(ContactEntity c) {
    return new ContactSummaryDto(
        c.getId(), c.getFirstName(), c.getLastName(), c.getEmail(), c.getPhone(), c.getRole());
  }

  private tools.jackson.databind.node.ObjectNode toAgentNode(AccountEntity a) {
    var node = new ObjectMapper().createObjectNode();
    node.put("id", a.getId().toString());
    node.put("key", a.getKey());
    node.put("name", a.getName());
    node.put("type", a.getType());
    node.put("industry", a.getIndustry());
    node.put("status", a.getStatus());
    node.put("owner", a.getOwner());
    node.put("contactCount", repository.countContacts(a.getId()));
    node.put("updatedAt", a.getUpdatedAt().toString());
    return node;
  }

  private tools.jackson.databind.node.ObjectNode contactToNode(ContactEntity c) {
    var node = new ObjectMapper().createObjectNode();
    node.put("id", c.getId().toString());
    node.put("firstName", c.getFirstName());
    node.put("lastName", c.getLastName());
    node.put("email", c.getEmail());
    node.put("phone", c.getPhone());
    node.put("role", c.getRole());
    node.put("primaryContact", c.isPrimaryContact());
    return node;
  }

  private void publish(String event, String title, String body, String severity, String linkPath) {
    eventPublisher.publishMessage(
        event,
        title,
        body,
        severity,
        List.of("portal-client-hub-view", "portal-client-hub-edit"),
        linkPath);
  }
}
