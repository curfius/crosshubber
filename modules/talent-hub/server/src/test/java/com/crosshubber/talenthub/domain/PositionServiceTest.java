package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** PositionService: create defaults, patch semantics, 409 version conflict. */
class PositionServiceTest {

  private PositionRepository positionRepository;
  private RfpRepository rfpRepository;
  private PositionService service;
  private RfpEntity rfp;
  private PositionEntity position;

  @BeforeEach
  void setUp() {
    positionRepository = mock(PositionRepository.class);
    rfpRepository = mock(RfpRepository.class);
    service = new PositionService(positionRepository, rfpRepository);
    rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClientId(UUID.randomUUID());
    whenEntityFound(rfp);
    position = new PositionEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(position, "id", UUID.randomUUID());
    position.setRfpId(rfp.getId());
    position.setTitle("Senior Java");
    org.mockito.Mockito.when(positionRepository.save(any(PositionEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    org.mockito.Mockito.when(rfpRepository.findById(rfp.getId())).thenReturn(Optional.of(rfp));
    org.mockito.Mockito.when(positionRepository.findById(position.getId()))
        .thenReturn(Optional.of(position));
    org.mockito.Mockito.when(positionRepository.findByRfpIdOrderByOrderIndexAsc(rfp.getId()))
        .thenReturn(List.of());
  }

  private void whenEntityFound(RfpEntity entity) {
    org.mockito.Mockito.when(rfpRepository.findById(entity.getId()))
        .thenReturn(Optional.of(entity));
  }

  @Test
  void createDefaultsHeadcountStatusAndOrderIndex() {
    RfpDtos.PositionDto created =
        service.create(
            rfp.getId(),
            new PositionService.PositionCreateRequest("Java dev", null, null, null, null),
            "u1");

    assertThat(created.headcount()).isEqualTo(1);
    assertThat(created.status()).isEqualTo("open");
    assertThat(created.orderIndex()).isEqualTo(0);
    assertThat(created.serviceType()).isNull();
  }

  @Test
  void createRejectsUnknownRfpAndBadValues() {
    assertThatThrownBy(
            () ->
                service.create(
                    UUID.randomUUID(),
                    new PositionService.PositionCreateRequest("x", null, null, null, null),
                    "u1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");

    whenEntityFound(rfp);
    assertThatThrownBy(
            () ->
                service.create(
                    rfp.getId(),
                    new PositionService.PositionCreateRequest("x", 0, null, null, null),
                    "u1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("headcount");

    whenEntityFound(rfp);
    assertThatThrownBy(
            () ->
                service.create(
                    rfp.getId(),
                    new PositionService.PositionCreateRequest("x", 1, " Perm ", null, null),
                    "u1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("serviceType");
  }

  @Test
  void patchAppliesOnlyProvidedFields() {
    RfpDtos.PositionDto patched =
        service.patch(
            position.getId(),
            new PositionService.PositionPatchRequest(
                null, null, 2, "direct", null, "filling", null),
            "u1");

    assertThat(patched.headcount()).isEqualTo(2);
    assertThat(patched.serviceType()).isEqualTo("direct");
    assertThat(patched.status()).isEqualTo("filling");
    assertThat(patched.title()).isEqualTo("Senior Java");
  }

  @Test
  void patchDetectsVersionConflict() {
    org.springframework.test.util.ReflectionTestUtils.setField(position, "version", 3);

    assertThatThrownBy(
            () ->
                service.patch(
                    position.getId(),
                    new PositionService.PositionPatchRequest(
                        2, "Changed", null, null, null, null, null),
                    "u1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    assertThat(position.getTitle()).isEqualTo("Senior Java");
  }

  @Test
  void patchRejectsUnknownStatus() {
    assertThatThrownBy(
            () ->
                service.patch(
                    position.getId(),
                    new PositionService.PositionPatchRequest(
                        null, null, null, null, null, "lost", null),
                    "u1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
  }
}
