package com.crosshubber.clienthub.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.clienthub.domain.ContactDtos.ContactDto;
import com.crosshubber.clienthub.domain.ContactDtos.CreateRequest;
import com.crosshubber.clienthub.domain.ContactDtos.UpdateRequest;
import com.crosshubber.clienthub.events.PortalEventPublisher;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Contact CRUD + search service. */
@Service
public class ContactService {

  private final ContactRepository repository;
  private final AccountRepository accountRepository;
  private final PortalEventPublisher eventPublisher;

  public ContactService(
      ContactRepository repository,
      AccountRepository accountRepository,
      PortalEventPublisher eventPublisher) {
    this.repository = repository;
    this.accountRepository = accountRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional(readOnly = true)
  public Page<ContactDto> list(UUID accountId, String query, Pageable pageable) {
    if (query != null && query.isBlank()) {
      query = null;
    }
    return repository.search(accountId, query, pageable).map(this::toDto);
  }

  @Transactional(readOnly = true)
  public ContactDto detail(UUID id) {
    return toDto(getOrThrow(id));
  }

  @Transactional
  public ContactDto create(CreateRequest req, String actorName) {
    AccountEntity account = getAccountOrThrow(req.accountId());
    ContactEntity contact = new ContactEntity();
    contact.setId(UUID.randomUUID());
    contact.setAccount(account);
    applyCreateRequest(contact, req);
    contact.setCreatedAt(Instant.now());
    contact.setUpdatedAt(Instant.now());
    ContactEntity saved = repository.save(contact);
    publish("contact.created", "Contact created", saved.getFirstName() + " " + saved.getLastName(),
        "info", "/contacts/" + saved.getId());
    return toDto(saved);
  }

  @Transactional
  public ContactDto update(UUID id, UpdateRequest req, String actorName) {
    ContactEntity contact = getOrThrow(id);
    AccountEntity account = getAccountOrThrow(req.accountId());
    contact.setAccount(account);
    applyUpdateRequest(contact, req);
    contact.setUpdatedAt(Instant.now());
    ContactEntity saved = repository.save(contact);
    publish("contact.updated", "Contact updated", saved.getFirstName() + " " + saved.getLastName(),
        "info", "/contacts/" + saved.getId());
    return toDto(saved);
  }

  @Transactional
  public void delete(UUID id, String actorName) {
    ContactEntity contact = getOrThrow(id);
    repository.delete(contact);
    publish("contact.deleted", "Contact deleted",
        contact.getFirstName() + " " + contact.getLastName(), "warning", null);
  }

  @Transactional(readOnly = true)
  public ObjectNode agentListContacts(UUID accountId, String query, int limit) {
    Pageable pageable = Pageable.ofSize(Math.min(limit, 50)).withPage(0);
    if (query != null && query.isBlank()) {
      query = null;
    }
    Page<ContactEntity> page = repository.search(accountId, query, pageable);
    var node = new ObjectMapper().createObjectNode();
    var contacts = node.putArray("contacts");
    for (ContactEntity c : page.getContent()) {
      contacts.add(toNode(c));
    }
    node.put("total", page.getTotalElements());
    return node;
  }

  private ContactEntity getOrThrow(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));
  }

  private AccountEntity getAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
  }

  private void applyCreateRequest(ContactEntity contact, CreateRequest req) {
    contact.setFirstName(req.firstName());
    contact.setLastName(req.lastName());
    contact.setEmail(req.email());
    contact.setPhone(req.phone());
    contact.setRole(req.role());
    contact.setPrimaryContact(req.primaryContact());
    contact.setNotes(req.notes());
  }

  private void applyUpdateRequest(ContactEntity contact, UpdateRequest req) {
    contact.setFirstName(req.firstName());
    contact.setLastName(req.lastName());
    contact.setEmail(req.email());
    contact.setPhone(req.phone());
    contact.setRole(req.role());
    contact.setPrimaryContact(req.primaryContact());
    contact.setNotes(req.notes());
  }

  private ContactDto toDto(ContactEntity c) {
    return new ContactDto(
        c.getId(),
        c.getAccount().getId(),
        c.getFirstName(),
        c.getLastName(),
        c.getEmail(),
        c.getPhone(),
        c.getRole(),
        c.isPrimaryContact(),
        c.getUpdatedAt());
  }

  private tools.jackson.databind.node.ObjectNode toNode(ContactEntity c) {
    var node = new ObjectMapper().createObjectNode();
    node.put("id", c.getId().toString());
    node.put("accountId", c.getAccount().getId().toString());
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
