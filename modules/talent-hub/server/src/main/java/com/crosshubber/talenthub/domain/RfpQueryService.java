package com.crosshubber.talenthub.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.docs.DocumentService;
import com.crosshubber.talenthub.domain.RfpDtos.RfpDetailDto;

/**
 * Read side (P2.1): filtered RFP list, drafts-grid rows (with client names) and the full detail
 * aggregate (client card, positions, processes, submissions, phase history, document metadata).
 * Reads are open to every staffing role (controller).
 */
@Service
public class RfpQueryService {

  private final ClientService clientService;
  private final ClientRepository clientRepository;
  private final RfpRepository rfpRepository;
  private final PositionRepository positionRepository;
  private final CandidateProcessRepository processRepository;
  private final SubmissionRepository submissionRepository;
  private final RfpPhaseHistoryRepository historyRepository;
  private final DocumentService documentService;

  public RfpQueryService(
      ClientService clientService,
      ClientRepository clientRepository,
      RfpRepository rfpRepository,
      PositionRepository positionRepository,
      CandidateProcessRepository processRepository,
      SubmissionRepository submissionRepository,
      RfpPhaseHistoryRepository historyRepository,
      DocumentService documentService) {
    this.clientService = clientService;
    this.clientRepository = clientRepository;
    this.rfpRepository = rfpRepository;
    this.positionRepository = positionRepository;
    this.processRepository = processRepository;
    this.submissionRepository = submissionRepository;
    this.historyRepository = historyRepository;
    this.documentService = documentService;
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
        historyRepository.findByRfpIdOrderByAtAsc(rfpId).stream().map(RfpDtos::toDto).toList(),
        documentMetas(rfp.getDocumentRefs()));
  }

  /** Drafts-grid rows: phase=DRAFT, newest first, with each RFP's client name resolved. */
  @Transactional(readOnly = true)
  public List<RfpDtos.RfpDraftRow> draftRows() {
    List<RfpEntity> drafts = rfpRepository.findByPhaseOrderByCreatedAtDesc(RfpPhase.DRAFT);
    Map<UUID, String> clientNames = namesOf(drafts.stream().map(RfpEntity::getClientId).toList());
    return drafts.stream()
        .map(e -> new RfpDtos.RfpDraftRow(RfpDtos.toDto(e), clientNames.get(e.getClientId())))
        .toList();
  }

  /** Resolves client names with a single id-batch query (no N+1). */
  private Map<UUID, String> namesOf(List<UUID> clientIds) {
    Map<UUID, String> names = new HashMap<>();
    for (ClientEntity client : clientRepository.findAllById(clientIds)) {
      names.put(client.getId(), client.getName());
    }
    return names;
  }

  /** Metadata per ref; unknown/external refs fall back to name = ref. */
  private List<RfpDtos.DocumentMetaDto> documentMetas(List<String> refs) {
    return refs.stream()
        .map(
            ref -> {
              DocumentService.DocMeta meta = documentService.metadata(ref);
              if (meta == null) {
                return new RfpDtos.DocumentMetaDto(ref, ref, null, null);
              }
              return new RfpDtos.DocumentMetaDto(ref, meta.name(), meta.mimeType(), meta.size());
            })
        .toList();
  }
}
