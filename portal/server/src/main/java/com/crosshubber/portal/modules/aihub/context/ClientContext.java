package com.crosshubber.portal.modules.aihub.context;

import java.util.List;

/**
 * Client-supplied portion of the session context pack (AI plan A2). Only navigation state is
 * trusted from the client — identity fields (user, tenant, contentVersion) are filled server-side
 * by {@link SessionContextBuilder}. Absent {@code context} = pre-context behavior (backwards
 * compatible).
 */
public record ClientContext(Location location, List<OpenTab> openTabs, String workspace) {

  /** Active tab/app location as reported by the shell. */
  public record Location(String tabKey, String appKey, String modulePath) {}

  /** Open tab: {@code moduleKey:contentKey} + title. */
  public record OpenTab(String key, String title) {}
}
