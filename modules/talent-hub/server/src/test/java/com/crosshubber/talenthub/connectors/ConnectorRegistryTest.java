package com.crosshubber.talenthub.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.config.TalentHubProperties;
import com.crosshubber.talenthub.settings.CryptoService;
import com.crosshubber.talenthub.settings.ModuleSettingEntity;
import com.crosshubber.talenthub.settings.ModuleSettingRepository;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registry (P4.1/P4.2): default resolution for every category, pong, unknown-kind degrade, outbox.
 */
class ConnectorRegistryTest {

  private static final String CRYPTO_KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private ModuleSettingsService settings;
  private OutboxRepository outboxRepository;
  private OutboxService outbox;
  private ConnectorRegistry registry;

  @BeforeEach
  void setUp() {
    ModuleSettingRepository repo = mock(ModuleSettingRepository.class);
    Map<String, ModuleSettingEntity> store = new ConcurrentHashMap<>();
    when(repo.save(any(ModuleSettingEntity.class)))
        .thenAnswer(
            inv -> {
              ModuleSettingEntity entity = inv.getArgument(0);
              store.put(entity.getKey(), entity);
              return entity;
            });
    when(repo.findByKey(any(String.class)))
        .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    TalentHubProperties props = new TalentHubProperties();
    props.setCryptoKey(CRYPTO_KEY);
    settings = new ModuleSettingsService(repo, new CryptoService(props), props, new JsonMapper());

    outboxRepository = mock(OutboxRepository.class);
    List<OutboxEntity> savedRows = new ArrayList<>();
    when(outboxRepository.save(any(OutboxEntity.class)))
        .thenAnswer(
            inv -> {
              OutboxEntity row = inv.getArgument(0);
              savedRows.add(row);
              return row;
            });
    when(outboxRepository.findTop50ByOrderByAtDesc()).thenAnswer(inv -> List.copyOf(savedRows));
    when(outboxRepository.findTop50ByKindOrderByAtDesc(any(String.class)))
        .thenAnswer(
            inv ->
                savedRows.stream()
                    .filter(row -> row.getKind().equals(inv.getArgument(0)))
                    .collect(Collectors.toList()));
    outbox = new OutboxService(outboxRepository);

    registry =
        new ConnectorRegistry(
            List.of(
                new EmulatedCrmConnector(outbox),
                connector(ConnectorCategory.CRM, "salesforce"),
                connector(ConnectorCategory.TALENT_SOURCE, "emulated"),
                connector(ConnectorCategory.DOCUMENT_STORE, "fake"),
                new EmulatedMessagingConnector(outbox),
                new EmulatedHrisConnector(outbox)),
            settings);
  }

  private static ModuleConnector connector(ConnectorCategory category, String kind) {
    return new ModuleConnector() {
      @Override
      public ConnectorCategory category() {
        return category;
      }

      @Override
      public String kind() {
        return kind;
      }
    };
  }

  @Test
  void everyCategoryResolvesByDefault() {
    for (ConnectorCategory category : ConnectorCategory.values()) {
      assertThat(registry.resolve(category)).as(category.key()).isNotNull();
    }
  }

  @Test
  void configuredKindWinsAndUnknownKindDegradesInsteadOfFailing() {
    assertThat(registry.resolve(ConnectorCategory.CRM).kind()).isEqualTo("emulated");

    settings.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null,
            null,
            null,
            Map.of("crm", new ModuleSettingsService.ConnectorUpsert("salesforce", Map.of()))));
    assertThat(registry.resolve(ConnectorCategory.CRM).kind()).isEqualTo("salesforce");

    settings.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null,
            null,
            null,
            Map.of("crm", new ModuleSettingsService.ConnectorUpsert("not-a-connector", null))));
    assertThat(registry.resolve(ConnectorCategory.CRM).kind()).isEqualTo("emulated");
  }

  @Test
  void documentStoreResolvesTheLegacyFakeKind() {
    assertThat(registry.resolve(ConnectorCategory.DOCUMENT_STORE).kind()).isEqualTo("fake");
  }

  @Test
  void emulatedConnectorsAnswerTheTestActionWithPong() {
    for (ConnectorCategory category : ConnectorCategory.values()) {
      if (category == ConnectorCategory.CRM
          && "salesforce".equals(registry.resolve(category).kind())) {
        continue;
      }
      Map<String, Object> result = registry.test(category);
      assertThat(Boolean.TRUE.equals(result.get("ok"))).as(category.key()).isTrue();
      assertThat(String.valueOf(result.get("detail"))).contains("pong");
    }
  }

  @Test
  void emulatedConnectorsWriteOutboxRows() {
    new EmulatedCrmConnector(outbox).logOpportunity("Acme", "joined");
    new EmulatedMessagingConnector(outbox).send("ops@crosshubber", "welcome", "hello");
    String employeeId = new EmulatedHrisConnector(outbox).onboard("proc-1", "Dan Silva");
    assertThat(employeeId).startsWith("emp-");
    assertThat(outbox.latest(null)).hasSize(3);
    List<String> kinds =
        outbox.latest(null).stream().map(OutboxEntity::getKind).map(String::valueOf).toList();
    assertThat(kinds).containsExactlyInAnyOrder("crm", "messaging", "hris");
    assertThat(outbox.latest("hris").get(0).getSubject()).contains("Dan Silva");
  }
}
