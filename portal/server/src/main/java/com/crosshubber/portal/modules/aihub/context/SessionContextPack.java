package com.crosshubber.portal.modules.aihub.context;

import java.util.List;

/**
 * Compact, structured portal session context carried with every chat request (AI plan A1: the model
 * answers with awareness of who is asking, where they are, and what is open).
 *
 * <p>Identity fields ({@code user}, {@code tenant}, {@code contentVersion}) are
 * <strong>server-authoritative</strong> — filled by {@link SessionContextBuilder} from the
 * authenticated principal and instance state, never trusted from the client. No secrets, no tokens,
 * no other users' data.
 */
public record SessionContextPack(
    User user,
    Tenant tenant,
    Location location,
    List<OpenTab> openTabs,
    String workspace,
    int contentVersion) {

  /** Signed-in user (display name + portal roles). Server-filled. */
  public record User(String name, List<String> roles) {}

  /** Tenant slug + effective locale (i18n default language). Server-filled. */
  public record Tenant(String slug, String locale) {}

  /**
   * Where the user is: active tab key ({@code moduleKey:contentKey}), active app key (module key)
   * and optional module-internal path. Client-supplied.
   */
  public record Location(String tabKey, String appKey, String modulePath) {}

  /** An open tab — key + title only. Client-supplied. */
  public record OpenTab(String key, String title) {}
}
