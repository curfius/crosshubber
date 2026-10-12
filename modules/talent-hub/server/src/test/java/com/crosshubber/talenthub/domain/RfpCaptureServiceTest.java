package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Capture pipeline (P1.3): TX split, client resolution, fail-soft analysis, published event. */
class RfpCaptureServiceTest {

  private RfpRepository rfpRepository;
  private ClientRepository clientRepository;
  private RfpAnalysisService analysisService;
  private PortalEventPublisher publisher;
  private RfpCaptureService service;

  @BeforeEach
  void setUp() {
    rfpRepository = mock(RfpRepository.class);
    clientRepository = mock(ClientRepository.class);
    analysisService = mock(RfpAnalysisService.class);
    publisher = mock(PortalEventPublisher.class);
    service =
        new RfpCaptureService(
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            rfpRepository,
            clientRepository,
            analysisService,
            publisher);
    when(rfpRepository.save(any(RfpEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    when(clientRepository.save(any(ClientEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    when(analysisService.analyze(anyString(), any(), any()))
        .thenReturn(
            new RfpAnalysisService.AnalysisResult(
                Map.of("status", "configured", "positions", List.of()),
                "Extracted",
                null,
                Map.of()));
  }

  @Test
  void captureCreatesDraftRunsAnalysisOutsideTxAndPublishes() {
    RfpDtos.RfpDto rfp =
        service.capture(
            new RfpCaptureService.CaptureRequest(
                null,
                new RfpCaptureService.NewClient("Acme", null, null),
                "rfp",
                "tm",
                "Java squad",
                null,
                "Need two senior Java engineers.",
                List.of()),
            "Dev Admin");

    assertThat(rfp.phase()).isEqualTo("draft");
    assertThat(rfp.source()).isEqualTo("manual");
    assertThat(rfp.title()).isEqualTo("Java squad");
    // Analysis persisted + title backfill was skipped (hint title non-blank).
    assertThat(rfp.analysis()).containsEntry("status", "configured");
    // Event published with recruiter+admin audience, never staffing-user.
    verify(publisher)
        .publishMessage(
            eq("rfp.captured"),
            anyString(),
            any(),
            eq("info"),
            argThat(roles -> roles.equals(List.of(ModuleRoles.RECRUITER, ModuleRoles.ADMIN))),
            any());
  }

  @Test
  void captureWithClientRefLinksExistingClientWithoutCreatingOne() {
    UUID clientId = UUID.randomUUID();
    when(clientRepository.existsById(clientId)).thenReturn(true);

    RfpDtos.RfpDto rfp =
        service.capture(
            new RfpCaptureService.CaptureRequest(
                clientId.toString(),
                null,
                null,
                null,
                null,
                null,
                "Body of the request.",
                List.of()),
            "Dev Admin");

    assertThat(rfp.clientId()).isEqualTo(clientId);
    verify(clientRepository, never()).save(any(ClientEntity.class));
    // Title backfilled from the analysis (hint was blank).
    assertThat(rfp.title()).isEqualTo("Extracted");
  }

  @Test
  void unknownClientRefFails() {
    when(clientRepository.existsById(any())).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        UUID.randomUUID().toString(), null, null, null, null, null, "d", null),
                    "Dev Admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void clientRefAndNewClientAreMutuallyExclusive() {
    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        UUID.randomUUID().toString(),
                        new RfpCaptureService.NewClient("A", null, null),
                        null,
                        null,
                        null,
                        null,
                        "d",
                        null),
                    "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not both");
  }

  @Test
  void descriptionIsRequired() {
    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        null,
                        new RfpCaptureService.NewClient("A", null, null),
                        null,
                        null,
                        null,
                        null,
                        "  ",
                        null),
                    "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
  }

  @Test
  void enumAndDeadlineValidation() {
    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        null,
                        new RfpCaptureService.NewClient("A", null, null),
                        "po",
                        null,
                        null,
                        null,
                        "d",
                        null),
                    "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind");

    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        null,
                        new RfpCaptureService.NewClient("A", null, null),
                        null,
                        "staff",
                        null,
                        null,
                        "d",
                        null),
                    "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("serviceType");

    assertThatThrownBy(
            () ->
                service.capture(
                    new RfpCaptureService.CaptureRequest(
                        null,
                        new RfpCaptureService.NewClient("A", null, null),
                        null,
                        null,
                        null,
                        "soon",
                        "d",
                        null),
                    "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deadline");
  }

  @Test
  void unconfiguredAnalysisStillCreatesTheDraft() {
    when(analysisService.analyze(anyString(), any(), any()))
        .thenReturn(
            new RfpAnalysisService.AnalysisResult(
                Map.of("status", "unconfigured", "positions", List.of()), null, null, Map.of()));

    RfpDtos.RfpDto rfp =
        service.capture(
            new RfpCaptureService.CaptureRequest(
                null,
                new RfpCaptureService.NewClient("Acme", null, null),
                null,
                null,
                null,
                null,
                "Body.",
                List.of()),
            "Dev Admin");

    assertThat(rfp.phase()).isEqualTo("draft");
    assertThat(rfp.analysis()).containsEntry("status", "unconfigured");
    verify(rfpRepository, org.mockito.Mockito.times(2)).save(any(RfpEntity.class));
  }
}
