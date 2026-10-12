package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Capture pipeline orchestration (P1.3): TX scope resolves the client + creates the draft; the LLM
 * analysis runs strictly OUTSIDE the TX; a second TX persists the analysis + backfill; publishing
 * happens after (fail-soft). Capture is never blocked by the LLM.
 */
@Service
public class RfpCaptureService {

  public record NewClient(String name, String industry, String accountOwner) {}

  public record CaptureRequest(
      String clientRef,
      NewClient newClient,
      String kind,
      String serviceType,
      String title,
      String deadline,
      String description,
      List<String> documentRefs) {}

  private final TransactionTemplate tx;
  private final RfpRepository rfpRepository;
  private final ClientRepository clientRepository;
  private final RfpAnalysisService analysisService;
  private final PortalEventPublisher publisher;

  public RfpCaptureService(
      TransactionTemplate tx,
      RfpRepository rfpRepository,
      ClientRepository clientRepository,
      RfpAnalysisService analysisService,
      PortalEventPublisher publisher) {
    this.tx = tx;
    this.rfpRepository = rfpRepository;
    this.clientRepository = clientRepository;
    this.analysisService = analysisService;
    this.publisher = publisher;
  }

  public RfpDtos.RfpDto capture(CaptureRequest request, String actor) {
    if (request == null) {
      throw new IllegalArgumentException("body is required");
    }
    if (request.description() == null || request.description().isBlank()) {
      throw new IllegalArgumentException("description is required");
    }
    if (request.kind() != null && !List.of("rfp", "rfq").contains(request.kind())) {
      throw new IllegalArgumentException("kind must be rfp or rfq");
    }
    if (request.serviceType() != null && !List.of("tm", "direct").contains(request.serviceType())) {
      throw new IllegalArgumentException("serviceType must be tm or direct");
    }
    Instant deadlineHint = parseDeadline(request.deadline());

    // ── TX 1: client resolution + draft creation ──
    RfpEntity draft =
        tx.execute(
            status -> {
              UUID clientId = resolveClientId(request);
              RfpEntity rfp = new RfpEntity();
              rfp.setClientId(clientId);
              rfp.setTitle(request.title() == null ? "" : request.title());
              rfp.setKind(request.kind() == null ? "rfp" : request.kind());
              rfp.setServiceType(request.serviceType() == null ? "tm" : request.serviceType());
              rfp.setSource("manual");
              rfp.setDeadline(deadlineHint);
              rfp.setDescription(request.description());
              rfp.setDocumentRefs(
                  new ArrayList<>(
                      request.documentRefs() == null ? List.of() : request.documentRefs()));
              rfp.setCreatedBy(actor == null ? "unknown" : actor);
              return rfpRepository.save(rfp);
            });

    // ── OUTSIDE TX: LLM analysis (capture never blocked) ──
    RfpAnalysisService.AnalysisResult analysis =
        analysisService.analyze(request.description(), request.title(), request.deadline());

    // ── TX 2: analysis persistence + best-effort backfill ──
    RfpEntity saved =
        tx.execute(
            status -> {
              draft.setAnalysis(analysis.analysis());
              if (analysis.title() != null && draft.getTitle().isBlank()) {
                draft.setTitle(analysis.title());
              }
              if (analysis.deadline() != null && draft.getDeadline() == null) {
                draft.setDeadline(analysis.deadline());
              }
              if (!analysis.requirements().isEmpty()) {
                draft.setRequirements(analysis.requirements());
              }
              return rfpRepository.save(draft);
            });

    // ── OUTSIDE TX: publish (fail-soft) ──
    publisher.publishMessage(
        "rfp.captured",
        "RFP captured: " + (saved.getTitle().isBlank() ? "(untitled)" : saved.getTitle()),
        saved.getDescription(),
        "info",
        List.of(ModuleRoles.RECRUITER, ModuleRoles.ADMIN),
        null);
    return RfpDtos.toDto(saved);
  }

  private Instant parseDeadline(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (Exception e) {
      throw new IllegalArgumentException("deadline must be an ISO-8601 instant");
    }
  }

  /** Either an existing clientRef or a new client to create — never both, never neither. */
  private UUID resolveClientId(CaptureRequest request) {
    String clientRef = request.clientRef();
    NewClient newClient = request.newClient();
    if (clientRef != null && newClient != null) {
      throw new IllegalArgumentException("use either clientRef or newClient, not both");
    }
    if (clientRef != null) {
      UUID id;
      try {
        id = UUID.fromString(clientRef);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("clientRef must be a UUID");
      }
      if (!clientRepository.existsById(id)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "client not found");
      }
      return id;
    }
    if (newClient == null || newClient.name() == null || newClient.name().isBlank()) {
      throw new IllegalArgumentException("clientRef or newClient.name is required");
    }
    if (newClient.name().length() > 200) {
      throw new IllegalArgumentException("newClient.name must be at most 200 chars");
    }
    ClientEntity client = new ClientEntity();
    client.setName(newClient.name().trim());
    client.setIndustry(blankToNull(newClient.industry()));
    client.setAccountOwner(blankToNull(newClient.accountOwner()));
    return clientRepository.save(client).getId();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
