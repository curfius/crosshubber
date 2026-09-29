import { describe, expect, it } from 'vitest';
import { WorkbenchService } from '../../../features/workspaces/workspaces.store';
import { moduleContentId, type PortalModuleContent } from '../../../../core/models';
import { buildClientContext } from './session-context';

function ep(moduleKey: string): PortalModuleContent {
  return {
    moduleKey,
    contentKey: 'main',
    category: 'applications',
    name: `${moduleKey} name`,
    type: 'iframe',
    url: `https://${moduleKey}.example.com/index.html`,
    parentContentKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

/** Duck-typed workbench stub — buildClientContext only reads three signals. */
function workbenchStub(opts: {
  groups: Record<string, { id: string; tabs: Array<{ id: number; content: PortalModuleContent; instance: number }>; activeId: number | null }>;
  focused: string | null;
  workspace: string | null;
}): WorkbenchService {
  return {
    groups: () => opts.groups,
    focusedGroupId: () => opts.focused,
    activeWorkspace: () => opts.workspace,
  } as unknown as WorkbenchService;
}

describe('buildClientContext', () => {
  it('collects active location, open tabs and workspace from workbench signals', () => {
    const workbench = workbenchStub({
      groups: {
        g1: { id: 'g1', tabs: [{ id: 1, content: ep('ai-hub'), instance: 1 }], activeId: 1 },
        g2: { id: 'g2', tabs: [{ id: 2, content: ep('settings'), instance: 1 }], activeId: null },
      },
      focused: 'g1',
      workspace: 'Ops',
    });

    const ctx = buildClientContext(workbench);

    expect(ctx.location).toEqual({ tabKey: 'ai-hub:main', appKey: 'ai-hub' });
    expect(ctx.openTabs).toEqual([
      { key: 'ai-hub:main', title: 'ai-hub name' },
      { key: 'settings:main', title: 'settings name' },
    ]);
    expect(ctx.workspace).toBe('Ops');
  });

  it('yields null location when nothing is focused or open', () => {
    const workbench = workbenchStub({ groups: {}, focused: null, workspace: null });

    const ctx = buildClientContext(workbench);

    expect(ctx.location).toBeNull();
    expect(ctx.openTabs).toEqual([]);
    expect(ctx.workspace).toBeNull();
    expect(moduleContentId(ep('x'))).toBe('x:main');
  });
});
