package com.crosshubber.clienthub.domain;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs for contact API. */
public final class ContactDtos {

  private ContactDtos() {}

  /** Contact summary for list rows and account detail. */
  public record ContactDto(
      UUID id,
      UUID accountId,
      String firstName,
      String lastName,
      String email,
      String phone,
      String role,
      boolean primaryContact,
      java.time.Instant updatedAt) {}

  /** Contact create request. */
  public record CreateRequest(
      UUID accountId,
      @NotBlank @Size(max = 128) String firstName,
      @NotBlank @Size(max = 128) String lastName,
      @Email @Size(max = 256) String email,
      @Size(max = 128) String phone,
      @Size(max = 128) String role,
      boolean primaryContact,
      @Size(max = 4000) String notes) {}

  /** Contact update request. */
  public record UpdateRequest(
      UUID accountId,
      @NotBlank @Size(max = 128) String firstName,
      @NotBlank @Size(max = 128) String lastName,
      @Email @Size(max = 256) String email,
      @Size(max = 128) String phone,
      @Size(max = 128) String role,
      boolean primaryContact,
      @Size(max = 4000) String notes) {}
}
