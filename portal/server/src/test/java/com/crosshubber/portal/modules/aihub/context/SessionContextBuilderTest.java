package com.crosshubber.portal.modules.aihub.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.i18n.I18nService;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.security.PortalUser;

/**
 * AI plan A5: pack builder tests — server-authoritative identity, sanitization caps, stable
 * redaction of client input.
 */
class SessionContextBuilderTest {

  private I18nService i18nService;
  private SessionContextBuilder builder;

  @BeforeEach
  void setUp() {
    i18nService = Mockito.mock(I18nService.class);
    I18nSettingsEntity settings = new I18nSettingsEntity();
    settings.setDefaultLanguage("en-GB");
    settings.setContentVersion(7);
    Mockito.when(i18nService.getSettingsRow()).thenReturn(settings);

    PortalProperties props = new PortalProperties();
    props.setTenantSlug("dev");
    builder = new SessionContextBuilder(props, i18nService);
  }

  private static PortalUser user() {
    return new PortalUser("user-1", "Dev Admin", "dev@crosshubber.test", List.of("portal-admin"));
  }

  @Test
  void fillsServerAuthoritativeFieldsFromPrincipalAndSettings() {
    SessionContextPack pack = builder.build(user(), null);

    assertEquals("Dev Admin", pack.user().name());
    assertEquals(List.of("portal-admin"), pack.user().roles());
    assertEquals("dev", pack.tenant().slug());
    assertEquals("en-GB", pack.tenant().locale());
    assertEquals(7, pack.contentVersion());
    assertNull(pack.location());
    assertTrue(pack.openTabs().isEmpty());
    assertNull(pack.workspace());
  }

  @Test
  void clientCannotSpoofIdentityFields() {
    ClientContext context =
        new ClientContext(
            new ClientContext.Location("ai-hub:main", "ai-hub", "/chat"),
            List.of(new ClientContext.OpenTab("ai-hub:main", "AI Hub")),
            "Ops");

    SessionContextPack pack = builder.build(user(), context);

    assertEquals("Dev Admin", pack.user().name());
    assertEquals("dev", pack.tenant().slug());
    assertEquals(7, pack.contentVersion());
    assertEquals("ai-hub:main", pack.location().tabKey());
    assertEquals("Ops", pack.workspace());
  }

  @Test
  void capsAndTrimsClientStrings() {
    String longTitle = "x".repeat(500);
    ClientContext context =
        new ClientContext(
            new ClientContext.Location("  " + longTitle + " ", " app ", "  "),
            java.util.Arrays.asList(
                new ClientContext.OpenTab("  k1  ", " Title "),
                new ClientContext.OpenTab("  ", "no key — dropped"),
                null),
            "  ");

    SessionContextPack pack = builder.build(user(), context);

    assertEquals(120, pack.location().tabKey().length());
    assertEquals("app", pack.location().appKey());
    assertNull(pack.location().modulePath());
    assertEquals(1, pack.openTabs().size());
    assertEquals("k1", pack.openTabs().get(0).key());
    assertEquals("Title", pack.openTabs().get(0).title());
    assertNull(pack.workspace());
  }

  @Test
  void capsOpenTabsAtTwenty() {
    List<ClientContext.OpenTab> tabs =
        java.util.stream.IntStream.range(0, 50)
            .mapToObj(i -> new ClientContext.OpenTab("tab-" + i, "Tab " + i))
            .toList();

    SessionContextPack pack = builder.build(user(), new ClientContext(null, tabs, null));

    assertEquals(20, pack.openTabs().size());
    assertEquals("tab-0", pack.openTabs().get(0).key());
    assertEquals("tab-19", pack.openTabs().get(19).key());
  }

  @Test
  void rolesNullSafe() {
    SessionContextPack pack = builder.build(new PortalUser("u", "n", "e", null), null);
    assertTrue(pack.user().roles().isEmpty());
  }
}
