package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ClientService: validation, ordering, 404. */
class ClientServiceTest {

  private ClientRepository repo;
  private RfpRepository rfpRepository;
  private ClientService service;

  @BeforeEach
  void setUp() {
    repo = org.mockito.Mockito.mock(ClientRepository.class);
    rfpRepository = org.mockito.Mockito.mock(RfpRepository.class);
    service = new ClientService(repo, rfpRepository);
  }

  private ClientEntity entity(String name) {
    ClientEntity e = new ClientEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
    e.setName(name);
    return e;
  }

  @Test
  void createTrimsAndPersists() {
    org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));

    ClientService.ClientDto created =
        service.create(
            new ClientService.ClientCreateRequest(
                "  Acme Corp ",
                "Engineering",
                "Dev Admin",
                List.of(Map.of("name", "Jo", "role", "buyer")),
                null));

    assertThat(created.name()).isEqualTo("Acme Corp");
    assertThat(created.contacts()).hasSize(1);
    assertThat(created.notes()).isNull();
  }

  @Test
  void createValidatesNameAndContacts() {
    assertThatThrownBy(
            () ->
                service.create(new ClientService.ClientCreateRequest(null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                service.create(
                    new ClientService.ClientCreateRequest(
                        "x", null, null, List.of(Map.of()), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contacts");
    assertThatThrownBy(
            () ->
                service.create(
                    new ClientService.ClientCreateRequest("y".repeat(201), null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("200");
  }

  @Test
  void listSortsByCreatedAt() {
    ClientEntity first = entity("First");
    org.springframework.test.util.ReflectionTestUtils.setField(
        first, "createdAt", java.time.Instant.now().minusSeconds(60));
    ClientEntity second = entity("Second");
    org.springframework.test.util.ReflectionTestUtils.setField(
        second, "createdAt", java.time.Instant.now());
    org.mockito.Mockito.when(repo.findAll()).thenReturn(List.of(second, first));

    assertThat(service.list().get(0).name()).isEqualTo("First");
    assertThat(service.list().get(1).name()).isEqualTo("Second");
  }

  @Test
  void getUnknownIdIsNotFound() {
    org.mockito.Mockito.when(repo.findById(org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.get(UUID.randomUUID()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("404");
  }
}
