package com.crosshubber.clienthub.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTOs for account API. */
public final class AccountDtos {

  private AccountDtos() {}

  public static final String KEY_RE = "[a-z0-9][a-z0-9-]{0,63}";

  /** Account summary for list rows. */
  public record AccountDto(
      UUID id,
      String key,
      String name,
      String type,
      String industry,
      String status,
      String owner,
      long contactCount,
      Instant updatedAt) {}

  /** Full account detail. */
  public record AccountDetailDto(
      UUID id,
      String key,
      String name,
      String type,
      String industry,
      String website,
      String phone,
      String email,
      Map<String, String> address,
      String owner,
      List<String> tags,
      String status,
      String notes,
      Map<String, String> externalRefs,
      List<ContactSummaryDto> contacts,
      Instant createdAt,
      Instant updatedAt) {}

  /** Contact embedded in account detail. */
  public record ContactSummaryDto(
      UUID id, String firstName, String lastName, String email, String phone, String role) {}

  /** Account create request. */
  public record CreateRequest(
      @NotBlank @Size(max = 64) @Pattern(regexp = KEY_RE) String key,
      @NotBlank @Size(max = 256) String name,
      String type,
      String industry,
      @Size(max = 256) String website,
      @Size(max = 128) String phone,
      @Size(max = 256) String email,
      Map<String, String> address,
      @Size(max = 256) String owner,
      List<String> tags,
      String status,
      @Size(max = 4000) String notes,
      Map<String, String> externalRefs) {}

  /** Account update request. */
  public record UpdateRequest(
      @NotBlank @Size(max = 256) String name,
      String type,
      String industry,
      @Size(max = 256) String website,
      @Size(max = 128) String phone,
      @Size(max = 256) String email,
      Map<String, String> address,
      @Size(max = 256) String owner,
      List<String> tags,
      String status,
      @Size(max = 4000) String notes,
      Map<String, String> externalRefs) {}

  /** Agent tool payload. */
  public record AgentAccountNode(
      UUID id,
      String key,
      String name,
      String type,
      String industry,
      String status,
      String owner,
      long contactCount,
      String updatedAt) {}

  public record AgentAccountDetailNode(
      AgentAccountNode account,
      List<ContactSummaryDto> contacts) {}

  public record AgentNextActionNode(
      UUID accountId, String suggestion, String rationale, String priority) {}
}
