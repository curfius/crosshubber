package com.crosshubber.portal.modules.aihub.context;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.i18n.I18nService;
import com.crosshubber.portal.security.PortalUser;

/**
 * Builds the server-side {@link SessionContextPack} from the authenticated principal plus the
 * client-supplied {@link ClientContext} (AI plan A1/A2).
 *
 * <p>Identity fields are server-authoritative: user name/roles come from {@link PortalUser}, the
 * tenant slug from portal properties, locale and {@code contentVersion} from i18n settings. The
 * client may only contribute navigation state ({@code location}, {@code openTabs}, {@code
 * workspace}). All client strings are trimmed, length-capped and blank-stripped; the tab list is
 * capped so the prompt stays inside its budget.
 */
@Service
public class SessionContextBuilder {

  private static final int MAX_OPEN_TABS = 20;
  private static final int MAX_FIELD_LENGTH = 120;

  private final PortalProperties props;
  private final I18nService i18nService;

  public SessionContextBuilder(PortalProperties props, I18nService i18nService) {
    this.props = props;
    this.i18nService = i18nService;
  }

  /**
   * Builds the pack. Never returns null — a missing client context yields a pack with only
   * server-authoritative fields populated.
   *
   * @param user authenticated principal (name/roles authority)
   * @param context client-supplied navigation state, may be null
   * @return sanitized, server-completed session context pack
   */
  public SessionContextPack build(PortalUser user, ClientContext context) {
    var settings = i18nService.getSettingsRow();
    SessionContextPack.User identity =
        new SessionContextPack.User(
            cap(user.name()), user.roles() == null ? List.of() : List.copyOf(user.roles()));
    SessionContextPack.Tenant tenant =
        new SessionContextPack.Tenant(props.getTenantSlug(), settings.getDefaultLanguage());

    SessionContextPack.Location location = null;
    List<SessionContextPack.OpenTab> openTabs = List.of();
    String workspace = null;
    if (context != null) {
      location = toLocation(context.location());
      openTabs = toOpenTabs(context.openTabs());
      workspace = Texts.blankToNull(cap(Texts.string(context.workspace())));
    }
    return new SessionContextPack(
        identity, tenant, location, openTabs, workspace, settings.getContentVersion());
  }

  private SessionContextPack.Location toLocation(ClientContext.Location raw) {
    if (raw == null) {
      return null;
    }
    String tabKey = Texts.blankToNull(cap(Texts.string(raw.tabKey())));
    String appKey = Texts.blankToNull(cap(Texts.string(raw.appKey())));
    String modulePath = Texts.blankToNull(cap(Texts.string(raw.modulePath())));
    if (tabKey == null && appKey == null && modulePath == null) {
      return null;
    }
    return new SessionContextPack.Location(tabKey, appKey, modulePath);
  }

  private List<SessionContextPack.OpenTab> toOpenTabs(List<ClientContext.OpenTab> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<SessionContextPack.OpenTab> tabs = new ArrayList<>();
    for (ClientContext.OpenTab tab : raw) {
      if (tabs.size() >= MAX_OPEN_TABS) {
        break;
      }
      String key = Texts.blankToNull(cap(Texts.string(tab == null ? null : tab.key())));
      if (key == null) {
        continue;
      }
      String title = Texts.blankToNull(cap(Texts.string(tab == null ? null : tab.title())));
      tabs.add(new SessionContextPack.OpenTab(key, title));
    }
    return List.copyOf(tabs);
  }

  private static String cap(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > MAX_FIELD_LENGTH ? trimmed.substring(0, MAX_FIELD_LENGTH) : trimmed;
  }
}
