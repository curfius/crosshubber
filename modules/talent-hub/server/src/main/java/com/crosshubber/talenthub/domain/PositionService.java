package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Req-line CRUD (P2.2): create under an RFP, patch with optimistic version check (409 per house
 * convention). Write access is recruiter-or-better (controller).
 */
@Service
public class PositionService {

  private static final Set<String> STATUSES = Set.of("open", "filling", "filled", "cancelled");
  private static final Set<String> SERVICE_TYPES = Set.of("tm", "direct");

  public record PositionCreateRequest(
      String title,
      Integer headcount,
      String serviceType,
      Map<String, Object> requirements,
      Integer orderIndex) {}

  public record PositionPatchRequest(
      Integer version,
      String title,
      Integer headcount,
      String serviceType,
      Map<String, Object> requirements,
      String status,
      Integer orderIndex) {}

  private final PositionRepository positionRepository;
  private final RfpRepository rfpRepository;

  public PositionService(PositionRepository positionRepository, RfpRepository rfpRepository) {
    this.positionRepository = positionRepository;
    this.rfpRepository = rfpRepository;
  }

  @Transactional
  public RfpDtos.PositionDto create(UUID rfpId, PositionCreateRequest request, String actor) {
    RfpEntity rfp =
        rfpRepository
            .findById(rfpId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
    if (request.title() == null || request.title().isBlank()) {
      throw new IllegalArgumentException("title is required");
    }
    int headcount = request.headcount() == null ? 1 : request.headcount();
    if (headcount < 1) {
      throw new IllegalArgumentException("headcount must be at least 1");
    }
    if (request.serviceType() != null && !SERVICE_TYPES.contains(request.serviceType())) {
      throw new IllegalArgumentException("serviceType must be tm or direct");
    }
    List<PositionEntity> existing = positionRepository.findByRfpIdOrderByOrderIndexAsc(rfpId);
    PositionEntity entity = new PositionEntity();
    entity.setRfpId(rfp.getId());
    entity.setTitle(request.title().trim());
    entity.setHeadcount(headcount);
    entity.setServiceType(request.serviceType());
    if (request.requirements() != null) {
      entity.setRequirements(request.requirements());
    }
    entity.setStatus("open");
    entity.setOrderIndex(request.orderIndex() == null ? existing.size() : request.orderIndex());
    positionRepository.save(entity);
    return RfpDtos.toDto(entity);
  }

  @Transactional
  public RfpDtos.PositionDto patch(UUID id, PositionPatchRequest request, String actor) {
    PositionEntity entity =
        positionRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "position not found"));
    if (request == null) {
      throw new IllegalArgumentException("body is required");
    }
    if (request.version() != null && !request.version().equals(entity.getVersion())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "version conflict; reload and retry");
    }
    if (request.title() != null) {
      if (request.title().isBlank()) {
        throw new IllegalArgumentException("title cannot be blank");
      }
      entity.setTitle(request.title().trim());
    }
    if (request.headcount() != null) {
      if (request.headcount() < 1) {
        throw new IllegalArgumentException("headcount must be at least 1");
      }
      entity.setHeadcount(request.headcount());
    }
    if (request.serviceType() != null) {
      if (!SERVICE_TYPES.contains(request.serviceType())) {
        throw new IllegalArgumentException("serviceType must be tm or direct");
      }
      entity.setServiceType(request.serviceType());
    }
    if (request.status() != null) {
      if (!STATUSES.contains(request.status())) {
        throw new IllegalArgumentException("status must be open|filling|filled|cancelled");
      }
      entity.setStatus(request.status());
    }
    if (request.orderIndex() != null) {
      entity.setOrderIndex(request.orderIndex());
    }
    if (request.requirements() != null) {
      entity.setRequirements(request.requirements());
    }
    positionRepository.save(entity);
    return RfpDtos.toDto(entity);
  }
}
