import { inject } from '@angular/core';
import { moduleContentId, parseModuleContentId } from '../../../../core/models';
import { WorkbenchService } from '../../../features/workspaces/workspaces.store';
import type { ClientContext } from './chat-core.service';

/**
 * Builds the client-supplied session context pack (AI plan A2) from live workbench signals.
 * Only navigation state leaves the client — identity fields (user, tenant, contentVersion)
 * are filled server-side from the authenticated principal.
 */
export function buildClientContext(workbench: WorkbenchService): ClientContext {
  const groups = Object.values(workbench.groups());
  const focused = groups.find((g) => g.id === workbench.focusedGroupId());
  const active = focused?.tabs.find((t) => t.id === focused.activeId) ?? null;
  const openTabs = groups
    .flatMap((g) => g.tabs)
    .map((t) => ({ key: moduleContentId(t.content), title: t.content.name }));
  const activeId = active ? moduleContentId(active.content) : null;
  return {
    location: activeId
      ? { tabKey: activeId, appKey: parseModuleContentId(activeId).moduleKey }
      : null,
    openTabs,
    workspace: workbench.activeWorkspace(),
  };
}
