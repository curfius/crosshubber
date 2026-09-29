import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatCoreService, type ChatStreamEvent } from './chat-core.service';

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
    await expect(
      service.streamChat({ message: 'x', onContent: () => {} }),
    ).rejects.toThrow('no chat model configured');
  });

  it('throws with the server error message on non-ok responses', async () => {
    fetchStub.mockResolvedValue({
      ok: false,
      json: async () => ({ error: 'conversation not found' }),
    });
    await expect(
      service.streamChat({ message: 'x', onContent: () => {} }),
    ).rejects.toThrow('conversation not found');
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
