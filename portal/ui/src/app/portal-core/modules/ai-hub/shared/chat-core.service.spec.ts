import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatCoreService, sortConversations, type ChatStreamEvent } from './chat-core.service';

type FetchStub = ReturnType<typeof vi.fn>;

function sseResponse(frames: string[], conversationId = 'conv_1'): Response {
  const body = frames.map((f) => `data: ${f}\n\n`).join('') + 'data: [DONE]\n\n';
  return {
    ok: true,
    headers: new Headers({ 'X-Conversation-Id': conversationId }),
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(body));
        controller.close();
      },
    }),
  } as unknown as Response;
}

describe('ChatCoreService.streamChat', () => {
  let service: ChatCoreService;
  let fetchStub: FetchStub;

  beforeEach(() => {
    service = new ChatCoreService();
    fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends context and toolConfirmation in the request body', async () => {
    fetchStub.mockResolvedValue(sseResponse(['{"content":"hi"}']));
    await service.streamChat({
      conversationId: 'conv_9',
      message: 'hello',
      context: {
        location: { tabKey: 'ai-hub:main', appKey: 'ai-hub' },
        openTabs: [{ key: 'ai-hub:main', title: 'AI Hub' }],
        workspace: 'Ops',
      },
      toolConfirmation: { callId: 'call_1' },
      onContent: () => {},
    });

    const body = JSON.parse(fetchStub.mock.calls[0][1].body as string);
    expect(body.conversationId).toBe('conv_9');
    expect(body.message).toBe('hello');
    expect(body.context.location.tabKey).toBe('ai-hub:main');
    expect(body.context.workspace).toBe('Ops');
    expect(body.toolConfirmation).toEqual({ callId: 'call_1' });
  });

  it('forwards content deltas stripping leading newlines of the first chunk', async () => {
    fetchStub.mockResolvedValue(sseResponse(['{"content":"\\n\\nhello "}', '{"content":"world"}']));
    const chunks: string[] = [];
    const result = await service.streamChat({
      message: 'x',
      onContent: (c) => chunks.push(c),
    });
    expect(chunks.join('')).toBe('hello world');
    expect(result.conversationId).toBe('conv_1');
  });

  it('strips leading newlines split across several chunks', async () => {
    fetchStub.mockResolvedValue(
      sseResponse(['{"content":"\\n"}', '{"content":"\\n"}', '{"content":"hello"}']),
    );
    const chunks: string[] = [];
    await service.streamChat({ message: 'x', onContent: (c) => chunks.push(c) });
    expect(chunks.join('')).toBe('hello');
  });

  it('strips leading newlines of content emitted after tool frames', async () => {
    fetchStub.mockResolvedValue(
      sseResponse([
        '{"type":"tool_call","tool":"listModules"}',
        '{"content":"\\n\\nHere is what I found"}',
      ]),
    );
    const chunks: string[] = [];
    await service.streamChat({ message: 'x', onContent: (c) => chunks.push(c), onEvent: () => {} });
    expect(chunks.join('')).toBe('Here is what I found');
  });

  it('emits typed tool frames and keeps content separate', async () => {
    fetchStub.mockResolvedValue(
      sseResponse([
        '{"type":"tool_call","tool":"getShellConfig","module":null,"mutates":false}',
        '{"content":"working"}',
        '{"type":"tool_result","tool":"getShellConfig","status":"ok"}',
        '{"type":"confirmation_required","tool":"update_stage","callId":"call_7"}',
      ]),
    );
    const events: ChatStreamEvent[] = [];
    const chunks: string[] = [];
    await service.streamChat({
      message: 'x',
      onContent: (c) => chunks.push(c),
      onEvent: (e) => events.push(e),
    });
    expect(events.map((e) => e.type)).toEqual([
      'tool_call',
      'tool_result',
      'confirmation_required',
    ]);
    expect(events[0].tool).toBe('getShellConfig');
    expect(events[2].callId).toBe('call_7');
    expect(chunks.join('')).toBe('working');
  });

  it('throws on error frames with the upstream message', async () => {
    fetchStub.mockResolvedValue(sseResponse(['{"error":"no chat model configured"}']));
    await expect(service.streamChat({ message: 'x', onContent: () => {} })).rejects.toThrow(
      'no chat model configured',
    );
  });

  it('throws with the server error message on non-ok responses', async () => {
    fetchStub.mockResolvedValue({
      ok: false,
      json: async () => ({ error: 'conversation not found' }),
    });
    await expect(service.streamChat({ message: 'x', onContent: () => {} })).rejects.toThrow(
      'conversation not found',
    );
  });

  it('handles both spaced and unspaced data: prefixes', async () => {
    const body = 'data:{"content":"a"}\n\ndata: {"content":"b"}\n\ndata: [DONE]\n\n';
    fetchStub.mockResolvedValue({
      ok: true,
      headers: new Headers(),
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode(body));
          controller.close();
        },
      }),
    } as unknown as Response);
    const chunks: string[] = [];
    const result = await service.streamChat({ message: 'x', onContent: (c) => chunks.push(c) });
    expect(chunks.join('')).toBe('ab');
    expect(result.conversationId).toBeNull();
  });
});

describe('ChatCoreService.getMessages', () => {
  let service: ChatCoreService;
  let fetchStub: FetchStub;

  beforeEach(() => {
    service = new ChatCoreService();
    fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('strips leading newlines from assistant messages but keeps user content verbatim', async () => {
    fetchStub.mockResolvedValue({
      ok: true,
      json: async () => ({
        messages: [
          { role: 'user', content: '\nquestion', created_at: '2026-01-01T00:00:00Z' },
          { role: 'assistant', content: '\n\nanswer', created_at: '2026-01-01T00:00:01Z' },
        ],
      }),
    });
    const messages = await service.getMessages('conv_1');
    expect(messages.map((m) => m.content)).toEqual(['\nquestion', 'answer']);
  });

  it('returns an empty list on transport failure', async () => {
    fetchStub.mockRejectedValue(new Error('offline'));
    expect(await service.getMessages('conv_1')).toEqual([]);
  });
});

describe('ChatCoreService.updateConversation', () => {
  let service: ChatCoreService;
  let fetchStub: FetchStub;

  beforeEach(() => {
    service = new ChatCoreService();
    fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('patches title and pinned and returns the updated conversation', async () => {
    fetchStub.mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'conv_1',
        title: 'Renamed',
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:02Z',
        pinned: true,
      }),
    });
    const updated = await service.updateConversation('conv_1', { title: 'Renamed', pinned: true });
    expect(updated?.title).toBe('Renamed');
    expect(updated?.pinned).toBe(true);
    const [url, init] = fetchStub.mock.calls[0];
    expect(url).toBe('/api/ai-hub/conversations/conv_1');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body)).toEqual({ title: 'Renamed', pinned: true });
  });

  it('returns null on non-ok responses and transport errors', async () => {
    fetchStub.mockResolvedValue({ ok: false });
    expect(await service.updateConversation('conv_1', { pinned: true })).toBeNull();
    fetchStub.mockRejectedValue(new Error('offline'));
    expect(await service.updateConversation('conv_1', { pinned: true })).toBeNull();
  });
});

describe('sortConversations', () => {
  it('orders pinned first, then most recently updated', () => {
    const sorted = sortConversations([
      { id: 'a', title: 'A', created_at: '', updated_at: '2026-01-01T00:00:00Z', pinned: false },
      { id: 'b', title: 'B', created_at: '', updated_at: '2026-01-02T00:00:00Z', pinned: true },
      { id: 'c', title: 'C', created_at: '', updated_at: '2026-01-03T00:00:00Z', pinned: false },
    ]);
    expect(sorted.map((c) => c.id)).toEqual(['b', 'c', 'a']);
  });

  it('does not mutate the input list', () => {
    const input = [
      { id: 'a', title: 'A', created_at: '', updated_at: '2026-01-01T00:00:00Z', pinned: true },
      { id: 'b', title: 'B', created_at: '', updated_at: '2026-01-02T00:00:00Z', pinned: false },
    ];
    sortConversations(input);
    expect(input.map((c) => c.id)).toEqual(['a', 'b']);
  });
});
