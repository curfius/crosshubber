package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Match-run flow (P3.6): engine guard, ranking, persisted provenance. */
class MatchRunServiceTest {

  private RfpRepository rfpRepository;
  private CandidateProfileRepository candidateRepository;
  private MatchRunRepository matchRunRepository;
  private MatchRunService service;
  private RfpEntity rfp;

  private static CandidateProfileEntity candidate(
      String name, List<String> skills, String seniority) {
    CandidateProfileEntity c = new CandidateProfileEntity();
    ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
    c.setName(name);
    c.setSkills(skills);
    c.setSeniority(seniority);
    return c;
  }

  @BeforeEach
  void setUp() {
    rfpRepository = mock(RfpRepository.class);
    candidateRepository = mock(CandidateProfileRepository.class);
    matchRunRepository = mock(MatchRunRepository.class);
    service = new MatchRunService(rfpRepository, candidateRepository, matchRunRepository);
    rfp = new RfpEntity();
    ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClientId(UUID.randomUUID());
    rfp.setPhase(RfpPhase.APPROVED);
    rfp.getRequirements().put("skills", List.of("java", "spring"));
    rfp.getRequirements().put("seniority", "senior");
    when(rfpRepository.findById(rfp.getId())).thenReturn(java.util.Optional.of(rfp));
    when(candidateRepository.findAll())
        .thenReturn(
            List.of(
                candidate("strong", List.of("java", "spring", "kafka"), "senior"),
                candidate("weak", List.of("react"), "junior")));
    when(matchRunRepository.save(any(MatchRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void createMatchRunRanksAndPersistsWithEngineProvenance() {
    MatchRunEntity run = service.createMatchRun(rfp.getId(), 2, "rules", "Recruiter");

    assertThat(run.getEngine()).isEqualTo("rules");
    assertThat(run.getStatus()).isEqualTo(MatchRunEntity.RunStatus.DONE);
    assertThat(run.getResults()).hasSize(2);
    assertThat(run.getResults().get(0).get("name")).isEqualTo("strong");
    assertThat(run.getParams()).containsEntry("topN", 2);
  }

  @Test
  void createMatchRunRejectsNonRulesEngines() {
    assertThatThrownBy(() -> service.createMatchRun(rfp.getId(), null, "llm", "Recruiter"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("match runs rank deterministically");
  }

  @Test
  void createMatchRunUnknownRfpIsNotFound() {
    assertThatThrownBy(() -> service.createMatchRun(UUID.randomUUID(), null, "rules", "u"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void adHocRankReturnsScoredRows() {
    List<MatchingService.ScoredCandidate> ranked = service.rank(rfp.getRequirements(), 5);

    assertThat(ranked).hasSize(2);
    assertThat(ranked.get(0).rationale()).contains("java");
  }
}
