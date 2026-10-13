package com.crosshubber.talenthub.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RFP API shape (camelCase, Crosshubber-owned; shared by capture/workflow/controllers/tools). */
public final class RfpDtos {

  private RfpDtos() {}

  public record RfpDto(
      UUID id,
      UUID clientId,
      String title,
      String kind,
      String phase,
      String serviceType,
      String source,
      String deadline,
      String description,
      Map<String, Object> requirements,
      Map<String, Object> analysis,
      List<String> documentRefs,
      String createdBy,
      String createdAt,
      String updatedAt) {}

  public static RfpDto toDto(RfpEntity e) {
    return new RfpDto(
        e.getId(),
        e.getClientId(),
        e.getTitle(),
        e.getKind(),
        e.getPhase().value(),
        e.getServiceType(),
        e.getSource(),
        e.getDeadline() == null ? null : e.getDeadline().toString(),
        e.getDescription(),
        e.getRequirements(),
        e.getAnalysis(),
        e.getDocumentRefs(),
        e.getCreatedBy(),
        e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
        e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
  }

  public record PositionDto(
      UUID id,
      UUID rfpId,
      String title,
      Integer headcount,
      String serviceType,
      Map<String, Object> requirements,
      String status,
      Integer orderIndex) {}

  public static PositionDto toDto(PositionEntity e) {
    return new PositionDto(
        e.getId(),
        e.getRfpId(),
        e.getTitle(),
        e.getHeadcount(),
        e.getServiceType(),
        e.getRequirements(),
        e.getStatus(),
        e.getOrderIndex());
  }

  public record StageHistoryEntryDto(String stage, String at, String by, String note) {}

  public record ProcessDto(
      UUID id,
      UUID rfpId,
      UUID positionId,
      UUID candidateId,
      String stage,
      List<StageHistoryEntryDto> stageHistory,
      Map<String, Object> fit,
      String notes,
      String createdBy,
      String createdAt) {}

  public static ProcessDto toDto(CandidateProcessEntity e) {
    return new ProcessDto(
        e.getId(),
        e.getRfpId(),
        e.getPositionId(),
        e.getCandidateId(),
        e.getStage().value(),
        e.getStageHistory() == null
            ? List.of()
            : e.getStageHistory().stream()
                .map(
                    entry ->
                        new StageHistoryEntryDto(
                            text(entry.get("stage")),
                            text(entry.get("at")),
                            text(entry.get("by")),
                            text(entry.get("note"))))
                .toList(),
        e.getFit(),
        e.getNotes(),
        e.getCreatedBy(),
        e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
  }

  public record SubmissionDto(
      UUID id,
      UUID rfpId,
      List<UUID> processIds,
      String note,
      Map<String, Object> response,
      String sentBy,
      String sentAt,
      String respondedAt) {}

  public static SubmissionDto toDto(SubmissionEntity e) {
    return new SubmissionDto(
        e.getId(),
        e.getRfpId(),
        e.getProcessIds(),
        e.getNote(),
        e.getResponse(),
        e.getSentBy(),
        e.getSentAt() == null ? null : e.getSentAt().toString(),
        e.getRespondedAt() == null ? null : e.getRespondedAt().toString());
  }

  public record PhaseHistoryDto(
      UUID id, String from, String to, String trigger, String actor, String note, String at) {}

  public static PhaseHistoryDto toDto(RfpPhaseHistoryEntity e) {
    return new PhaseHistoryDto(
        e.getId(),
        e.getFromPhase() == null ? null : e.getFromPhase().value(),
        e.getToPhase().value(),
        e.getTrigger(),
        e.getActor(),
        e.getNote(),
        e.getAt() == null ? null : e.getAt().toString());
  }

  /** Drafts-grid row: the RFP plus its resolved client name (single clientIds query). */
  public record RfpDraftRow(RfpDto rfp, String clientName) {}

  /** Attachment metadata resolved from a document ref (name-only fallback for external refs). */
  public record DocumentMetaDto(String ref, String name, String mimeType, Long size) {}

  public record RfpDetailDto(
      RfpDto rfp,
      ClientService.ClientDto client,
      List<PositionDto> positions,
      List<ProcessDto> processes,
      List<SubmissionDto> submissions,
      List<PhaseHistoryDto> phaseHistory,
      List<DocumentMetaDto> documents) {}

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
