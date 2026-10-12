package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.domain.RfpDtos.RfpDetailDto;

/**
 * Read side (P2.1): filtered RFP list + the full detail aggregate (client card, positions,
 * processes, submissions, phase history). Reads are open to every staffing role (controller).
 */
@Service
public class RfpQueryService {

  private final ClientService clientService;
  private final RfpRepository rfpRepository;
  private final PositionRepository positionRepository;
  private final CandidateProcessRepository processRepository;
  private final SubmissionRepository submissionRepository;
  private final RfpPhaseHistoryRepository historyRepository;

  public RfpQueryService(
      ClientService clientService,
      RfpRepository rfpRepository,
      PositionRepository positionRepository,
      CandidateProcessRepository processRepository,
      SubmissionRepository submissionRepository,
      RfpPhaseHistoryRepository historyRepository) {
    this.clientService = clientService;
    this.rfpRepository = rfpRepository;
    this.positionRepository = positionRepository;
    this.processRepository = processRepository;
    this.submissionRepository = submissionRepository;
    this.historyRepository = historyRepository;
  }

  /** Registered RFPs, newest first, optional phase/client/serviceType/kind filters. */
  @Transactional(readOnly = true)
  public List<RfpDtos.RfpDto> list(RfpPhase phase, UUID clientId, String serviceType, String kind) {
    return rfpRepository.findAll().stream()
        .filter(r -> phase == null || r.getPhase() == phase)
        .filter(r -> clientId == null || clientId.equals(r.getClientId()))
        .filter(r -> serviceType == null || serviceType.equals(r.getServiceType()))
        .filter(r -> kind == null || kind.equals(r.getKind()))
        .sorted(
            java.util.Comparator.comparing(
                    RfpEntity::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(RfpEntity::getId))
        .map(RfpDtos::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public RfpDetailDto detail(UUID rfpId) {
    RfpEntity rfp =
        rfpRepository
            .findById(rfpId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
    return new RfpDetailDto(
        RfpDtos.toDto(rfp),
        clientService.get(rfp.getClientId()),
        positionRepository.findByRfpIdOrderByOrderIndexAsc(rfpId).stream()
            .map(RfpDtos::toDto)
            .toList(),
        processRepository.findByRfpIdOrderByCreatedAtAsc(rfpId).stream()
            .map(RfpDtos::toDto)
            .toList(),
        submissionRepository.findByRfpIdOrderBySentAtDesc(rfpId).stream()
            .map(RfpDtos::toDto)
            .toList(),
        historyRepository.findByRfpIdOrderByAtAsc(rfpId).stream().map(RfpDtos::toDto).toList());
  }
}
