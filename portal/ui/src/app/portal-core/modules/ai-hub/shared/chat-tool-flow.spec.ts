import { describe, expect, it } from 'vitest';
import { ChatToolFlow, toolStatusTone, waitUntilIdle } from './chat-tool-flow';
import type { ChatStreamEvent } from './chat-core.service';

function ev(type: string, extra: Record<string, unknown> = {}): ChatStreamEvent {
  return { type, ...extra };
}

describe('ChatToolFlow', () => {
  it('tracks a read call from tool_call to tool_result', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(
      ev('tool_call', { tool: 'list_projects', module: 'solutions', mutates: false }),
    );
    expect(flow.rows()).toHaveLength(1);
    expect(flow.rows()[0].status).toBe('running');
    expect(flow.rows()[0].module).toBe('solutions');

    flow.handleEvent(ev('tool_result', { tool: 'list_projects', status: 'ok' }));
    expect(flow.rows()).toHaveLength(1);
    expect(flow.rows()[0].status).toBe('ok');
    expect(flow.rows()[0].callId).toBeUndefined();
  });

  it('parks a mutating call and opens the confirmation from its frames', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(
      ev('tool_call', { tool: 'create_match_run', module: 'staffing', mutates: true }),
    );
    flow.handleEvent(
      ev('tool_result', {
        tool: 'create_match_run',
        status: 'needs_confirmation',
        callId: 'call_7',
      }),
    );
    flow.handleEvent(ev('confirmation_required', { tool: 'create_match_run', callId: 'call_7' }));

    expect(flow.pending()).toEqual({ tool: 'create_match_run', callId: 'call_7' });
    expect(flow.rows()[0].status).toBe('needs_confirmation');
    expect(flow.rows()[0].callId).toBe('call_7');
  });

  it('applies the confirmed-execution result to the parked row and closes the dialog', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(ev('tool_call', { tool: 'create_match_run', mutates: true }));
    flow.handleEvent(
      ev('tool_result', {
        tool: 'create_match_run',
        status: 'needs_confirmation',
        callId: 'call_7',
      }),
    );
    flow.handleEvent(ev('confirmation_required', { tool: 'create_match_run', callId: 'call_7' }));
    flow.clearPending();

    flow.handleEvent(ev('tool_result', { tool: 'confirmed:call_7', status: 'ok' }));

    expect(flow.pending()).toBeNull();
    expect(flow.rows()).toHaveLength(1);
    expect(flow.rows()[0].status).toBe('ok');
  });

  it('marks the row declined when the user cancels', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(ev('tool_call', { tool: 'create_match_run', mutates: true }));
    flow.handleEvent(
      ev('tool_result', { tool: 'create_match_run', status: 'needs_confirmation', callId: 'c1' }),
    );
    flow.handleEvent(ev('confirmation_required', { tool: 'create_match_run', callId: 'c1' }));

    flow.decline();

    expect(flow.pending()).toBeNull();
    expect(flow.rows()[0].status).toBe('declined');
  });

  it('ignores unknown frame types and frames without a call id', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(ev('something_new'));
    flow.handleEvent(ev('confirmation_required', { tool: 'x' }));
    expect(flow.rows()).toHaveLength(0);
    expect(flow.pending()).toBeNull();
  });

  it('appends a row for a result that never got a tool_call frame', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(ev('tool_result', { tool: 'orphan', status: 'error' }));
    expect(flow.rows()).toHaveLength(1);
    expect(flow.rows()[0].status).toBe('error');
  });

  it('caps the row list at 50 entries', () => {
    const flow = new ChatToolFlow();
    for (let i = 0; i < 60; i++) {
      flow.handleEvent(ev('tool_result', { tool: `t${i}`, status: 'ok' }));
    }
    expect(flow.rows()).toHaveLength(50);
    expect(flow.rows()[0].tool).toBe('t10');
    expect(flow.rows()[49].tool).toBe('t59');
  });

  it('reset clears rows and the pending confirmation', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(ev('tool_result', { tool: 't', status: 'ok' }));
    flow.reset();
    expect(flow.rows()).toHaveLength(0);
    expect(flow.pending()).toBeNull();
  });
});

describe('ChatToolFlow citations (G3)', () => {
  it('collects citation frames into the sources list', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(
      ev('citation', {
        tool: 'solutions_search_project_docs',
        citations: [
          { title: 'Proposal - scope', ref: 'fake:proposal-scope', snippet: 'fixed 180k' },
          { title: 'Architecture notes' },
        ],
      }),
    );
    expect(flow.citations()).toHaveLength(2);
    expect(flow.citations()[0]).toMatchObject({
      tool: 'solutions_search_project_docs',
      title: 'Proposal - scope',
      ref: 'fake:proposal-scope',
      snippet: 'fixed 180k',
    });
    expect(flow.citations()[1].ref).toBeUndefined();
  });

  it('skips citation items without a title and tolerates a missing array', () => {
    const flow = new ChatToolFlow();
    flow.handleEvent(
      ev('citation', {
        tool: 't',
        citations: [{}, null, 'nope', { title: '  ' }, { title: 'ok' }],
      }),
    );
    flow.handleEvent(ev('citation', { tool: 't' }));
    expect(flow.citations()).toHaveLength(1);
    expect(flow.citations()[0].title).toBe('ok');
  });

  it('caps the citation list at 50 and reset clears it', () => {
    const flow = new ChatToolFlow();
    for (let i = 0; i < 60; i++) {
      flow.handleEvent(ev('citation', { tool: 't', citations: [{ title: `d${i}` }] }));
    }
    expect(flow.citations()).toHaveLength(50);
    expect(flow.citations()[0].title).toBe('d10');
    flow.reset();
    expect(flow.citations()).toHaveLength(0);
  });
});

describe('toolStatusTone', () => {
  it('maps statuses to design-system badge variants with no raw hex colors', () => {
    expect(toolStatusTone('running')).toBe('ds-badge ds-badge-info');
    expect(toolStatusTone('ok')).toBe('ds-badge ds-badge-success');
    expect(toolStatusTone('confirmed')).toBe('ds-badge ds-badge-success');
    expect(toolStatusTone('denied')).toBe('ds-badge ds-badge-danger');
    expect(toolStatusTone('needs_confirmation')).toBe('ds-badge ds-badge-warning');
    expect(toolStatusTone('declined')).toBe('ds-badge ds-badge-default');
    for (const status of ['running', 'ok', 'denied', 'error', 'cap_reached', 'declined']) {
      expect(toolStatusTone(status)).not.toContain('#');
    }
  });
});

describe('waitUntilIdle', () => {
  it('resolves immediately when not busy', async () => {
    await expect(waitUntilIdle(() => false)).resolves.toBeUndefined();
  });

  it('waits until the busy flag flips', async () => {
    let busy = true;
    setTimeout(() => (busy = false), 150);
    await waitUntilIdle(() => busy, 20);
    expect(busy).toBe(false);
  });
});
