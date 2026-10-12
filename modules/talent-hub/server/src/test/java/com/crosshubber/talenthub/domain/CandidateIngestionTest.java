package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.docs.DocumentService;
import com.crosshubber.talenthub.docs.TalentSourceConnector;
import com.crosshubber.talenthub.events.PortalEventPublisher;

/** Scan extraction + sourcing inbox claim (P2.6). */
class CandidateIngestionTest {

  private CandidateProfileRepository candidateRepository;
  private TalentSourceConnector connector;
  private PortalEventPublisher publisher;
  private CandidateService candidateService;
  private SourcingInboxService inboxService;

  @BeforeEach
  void setUp() {
    candidateRepository = mock(CandidateProfileRepository.class);
    com.crosshubber.talenthub.docs.FakeDocumentSource fakeSource =
        new com.crosshubber.talenthub.docs.FakeDocumentSource(
            new com.crosshubber.talenthub.config.TalentHubProperties());
    candidateService =
        new CandidateService(
            candidateRepository,
            mock(CandidateProcessRepository.class),
            mock(PositionRepository.class),
            mock(RfpRepository.class),
            fakeSource);
    DocumentService realDocumentService =
        new DocumentService(
            new DocumentService.DocumentServiceConfig("target/test-documents"),
            fakeSource,
            new tools.jackson.databind.json.JsonMapper());
    connector = new com.crosshubber.talenthub.docs.EmulatedTalentSource();
    publisher = mock(PortalEventPublisher.class);
    inboxService =
        new SourcingInboxService(connector, candidateService, realDocumentService, publisher);
    when(candidateRepository.save(org.mockito.ArgumentMatchers.any(CandidateProfileEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(candidateRepository.findBySourceRef(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
  }

  @Test
  void scanExtractsSkillsSeniorityLanguagesAvailability() {
    String cv =
        """
        CV — Ana Pereira. Senior Java backend engineer.
        Summary: ten years of experience in Java and Spring Boot with PostgreSQL.
        Languages: Portuguese (native), English (fluent). Availability: immediate.
        """;

    CandidateService.CandidateDto parsed = candidateService.ingest("cv-ana", "Ana Pereira", cv);

    assertThat(parsed.skills()).contains("java", "spring boot", "postgresql");
    assertThat(parsed.seniority()).isEqualTo("senior");
    assertThat(parsed.languages()).contains("pt", "en");
    assertThat(parsed.availability()).isEqualTo("immediate");
    assertThat(parsed.sourceRef()).isEqualTo("cv-ana");
  }

  @Test
  void scanThroughThePortCoversTheFakeCorpus() throws Exception {
    CandidateService.CandidateDto scanned =
        candidateService.scan("fake:cv-carla", "Carla via scan", "u1");

    assertThat(scanned.name()).isEqualTo("Carla via scan");
    assertThat(scanned.skills()).isNotEmpty();
  }

  @Test
  void emptyScanTextIsRejected() {
    assertThatThrownBy(() -> candidateService.ingest("cv-x", null, "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void inboxListsUnclaimedFixturesAndClaimCreatesCandidate() {
    assertThat(inboxService.inbox()).hasSize(3);

    CandidateService.CandidateDto created = inboxService.claim("src-cv-dan", "Recruiter");

    // Claim produced a pool candidate from the linked fake corpus CV.
    assertThat(created.sourceRef()).isEqualTo("cv-ana");
    assertThat(created.name()).isEqualTo("Dan Silva");
    verifyAdded();
  }

  private void verifyAdded() {
    org.mockito.Mockito.verify(publisher)
        .publishMessage(
            org.mockito.ArgumentMatchers.eq("candidate.added"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("info"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void claimingTheSameRefTwiceFails() {
    inboxService.claim("src-cv-martina", "Recruiter");

    assertThatThrownBy(() -> inboxService.claim("src-cv-martina", "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already claimed");
  }

  @Test
  void claimFailsForUnknownRef() {
    assertThatThrownBy(() -> inboxService.claim("src-nope", "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown");
  }
}
