package com.crosshubber.talenthub.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Client intelligence CRUD (P1.1). MVP module-owned; the CRM connector (P4) enriches later. Write
 * access is manager+admin (controller-enforced); recruiters and users read.
 */
@Service
public class ClientService {

  /** API shape (camelCase, Crosshubber-owned). */
  public record ClientDto(
      UUID id,
      String name,
      String industry,
      String accountOwner,
      List<Map<String, Object>> contacts,
      String notes,
      String createdAt) {}

  /** Card + RFP history (P2.1); {@code active} = any non-terminal RFP. */
  public record ClientDetailDto(ClientDto client, List<RfpDtos.RfpDto> rfps, boolean active) {}

  public record ClientCreateRequest(
      String name,
      String industry,
      String accountOwner,
      List<Map<String, Object>> contacts,
      String notes) {}

  private final ClientRepository repo;
  private final RfpRepository rfpRepository;

  public ClientService(ClientRepository repo, RfpRepository rfpRepository) {
    this.repo = repo;
    this.rfpRepository = rfpRepository;
  }

  @Transactional(readOnly = true)
  public List<ClientDto> list() {
    return repo.findAll().stream()
        .sorted(java.util.Comparator.comparing(ClientEntity::getCreatedAt))
        .map(ClientService::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public ClientDto get(UUID id) {
    return repo.findById(id)
        .map(ClientService::toDto)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "client not found"));
  }

  /** Client card with RFP history, newest first (P2.1). */
  @Transactional(readOnly = true)
  public ClientDetailDto detail(UUID id) {
    ClientEntity entity =
        repo.findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "client not found"));
    List<RfpDtos.RfpDto> rfps =
        rfpRepository.findByClientIdOrderByCreatedAtDesc(id).stream().map(RfpDtos::toDto).toList();
    boolean active =
        rfps.stream()
            .anyMatch(
                r -> {
                  RfpPhase p = RfpPhase.fromString(r.phase());
                  return !p.isManualTerminal();
                });
    return new ClientDetailDto(toDto(entity), rfps, active);
  }

  @Transactional
  public ClientDto create(ClientCreateRequest request) {
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (request.name().length() > 200) {
      throw new IllegalArgumentException("name must be at most 200 chars");
    }
    List<Map<String, Object>> contacts =
        request.contacts() == null ? List.of() : request.contacts();
    for (Map<String, Object> contact : contacts) {
      if (contact == null || contact.isEmpty()) {
        throw new IllegalArgumentException("contacts must be non-empty objects");
      }
    }
    ClientEntity entity = new ClientEntity();
    entity.setName(request.name().trim());
    entity.setIndustry(blankToNull(request.industry()));
    entity.setAccountOwner(blankToNull(request.accountOwner()));
    entity.setContacts(new ArrayList<>(contacts));
    entity.setNotes(blankToNull(request.notes()));
    return toDto(repo.save(entity));
  }

  static ClientDto toDto(ClientEntity entity) {
    return new ClientDto(
        entity.getId(),
        entity.getName(),
        entity.getIndustry(),
        entity.getAccountOwner(),
        entity.getContacts(),
        entity.getNotes(),
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
