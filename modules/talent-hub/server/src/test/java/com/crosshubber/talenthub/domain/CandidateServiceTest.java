package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Talent pool (P2.6): filters, detail history, patch conflict, enrichment. */
class CandidateServiceTest {

  private CandidateProfileRepository candidateRepository;
  private CandidateProcessRepository processRepository;
  private PositionRepository positionRepository;
  private RfpRepository rfpRepository;
  private CandidateService service;

  @BeforeEach
  void setUp() {
    candidateRepository = mock(CandidateProfileRepository.class);
    processRepository = mock(CandidateProcessRepository.class);
    positionRepository = mock(PositionRepository.class);
    rfpRepository = mock(RfpRepository.class);
    service =
        new CandidateService(
            candidateRepository,
            processRepository,
            positionRepository,
            rfpRepository,
            org.mockito.Mockito.mock(com.crosshubber.talenthub.docs.DocumentSource.class));
    when(candidateRepository.save(org.mockito.ArgumentMatchers.any(CandidateProfileEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private CandidateProfileEntity candidate(String name, String kind, Instant enriched) {
    CandidateProfileEntity c = new CandidateProfileEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
    c.setSourceRef("manual:" + UUID.randomUUID());
    c.setName(name);
    c.setKind(kind);
    c.setLastEnrichedAt(enriched);
    return c;
  }

  private void all(CandidateProfileEntity... candidates) {
    when(candidateRepository.findAll()).thenReturn(List.of(candidates));
  }

  @Test
  void listFiltersByNameSkillKindAndStaleness() {
    CandidateProfileEntity fresh = candidate("Ana", "external", Instant.now());
    fresh.setSkills(List.of("java", "spring"));
    CandidateProfileEntity stale = candidate("Bruno", "external", null);
    stale.setSkills(List.of("react"));
    all(fresh, stale);

    assertThat(service.list("ana", null, null, false)).hasSize(1);
    assertThat(service.list("REACT", null, null, false)).hasSize(1);
    assertThat(service.list(null, "internal", null, false)).isEmpty();
    assertThat(service.list(null, null, null, true)).extracting("name").containsExactly("Bruno");
  }

  @Test
  void createValidatesNameAndKind() {
    assertThatThrownBy(
            () ->
                service.create(
                    new CandidateService.CandidateCreateRequest(
                        null, null, null, null, null, null, null, null, null, null, null),
                    "u1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");

    assertThatThrownBy(
            () ->
                service.create(
                    new CandidateService.CandidateCreateRequest(
                        "X", null, null, null, null, null, "robot", null, null, null, null),
                    "u1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind");
  }

  @Test
  void createDefaultsKindAndSourceRef() {
    CandidateService.CandidateDto created =
        service.create(
            new CandidateService.CandidateCreateRequest(
                "Carla", null, null, List.of("k8s"), null, null, null, null, null, null, null),
            "u1");

    assertThat(created.kind()).isEqualTo("external");
    assertThat(created.sourceRef()).startsWith("manual:");
    assertThat(created.lastEnrichedAt()).isNotNull();
  }

  @Test
  void patchAppliesProvidedFieldsAndRejectsVersionConflict() {
    CandidateProfileEntity current = candidate("Dan", "internal", Instant.now());
    org.springframework.test.util.ReflectionTestUtils.setField(current, "id", UUID.randomUUID());
    when(candidateRepository.findById(current.getId())).thenReturn(Optional.of(current));

    CandidateService.CandidateDto patched =
        service.patch(
            current.getId(),
            new CandidateService.CandidatePatchRequest(
                null,
                null,
                "principal",
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of("project", "Aurora", "allocationPct", 80),
                null,
                null),
            "u1");

    assertThat(patched.seniority()).isEqualTo("principal");
    assertThat(patched.assignment()).containsEntry("allocationPct", 80);

    assertThatThrownBy(
            () ->
                service.patch(
                    current.getId(),
                    new CandidateService.CandidatePatchRequest(
                        9, null, null, null, null, null, null, null, null, null, null),
                    "u1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void enrichStampsFreshnessAndClearsStaleStatus() {
    CandidateProfileEntity staleOne = candidate("Eva", "external", null);
    staleOne.setStatus(CandidateProfileEntity.ProfileStatus.STALE);
    org.springframework.test.util.ReflectionTestUtils.setField(staleOne, "id", UUID.randomUUID());
    when(candidateRepository.findById(staleOne.getId())).thenReturn(Optional.of(staleOne));

    CandidateService.CandidateDto enriched = service.enrich(staleOne.getId(), "u1");

    assertThat(enriched.lastEnrichedAt()).isNotNull();
    assertThat(enriched.stale()).isFalse();
  }

  @Test
  void detailBuildsComputedProcessHistory() {
    CandidateProfileEntity current = candidate("Ana", "external", Instant.now());
    UUID candidateId = UUID.randomUUID();
    org.springframework.test.util.ReflectionTestUtils.setField(current, "id", candidateId);
    when(candidateRepository.findById(candidateId)).thenReturn(Optional.of(current));

    RfpEntity rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClientId(UUID.randomUUID());
    rfp.setTitle("Java squad");
    PositionEntity position = new PositionEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(position, "id", UUID.randomUUID());
    position.setRfpId(rfp.getId());
    position.setTitle("Senior Java");
    CandidateProcessEntity process = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(process, "id", UUID.randomUUID());
    process.setRfpId(rfp.getId());
    process.setPositionId(position.getId());
    process.setCandidateId(candidateId);
    process.setStage(ProcessStage.OFFER);
    when(processRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId))
        .thenReturn(List.of(process));
    when(positionRepository.findAllById(List.of(position.getId()))).thenReturn(List.of(position));
    when(rfpRepository.findAllById(List.of(rfp.getId()))).thenReturn(List.of(rfp));

    CandidateService.CandidateDetailDto detail = service.detail(candidateId);

    assertThat(detail.history()).hasSize(1);
    assertThat(detail.history().get(0).rfpTitle()).isEqualTo("Java squad");
    assertThat(detail.history().get(0).positionTitle()).isEqualTo("Senior Java");
    assertThat(detail.history().get(0).stage()).isEqualTo("offer");
  }

  @Test
  void unknownCandidateSurfaces404() {
    when(candidateRepository.findById(UUID.randomUUID())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }
}
