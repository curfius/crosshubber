import { describe, expect, it } from 'vitest';
import { AiHubHandoffService } from './ai-hub-handoff.service';

describe('AiHubHandoffService', () => {
  it('parks a conversation id for the main assistant window', () => {
    const handoff = new AiHubHandoffService();
    expect(handoff.pending()).toBeNull();
    handoff.requestOpen('conv_1');
    expect(handoff.pending()).toBe('conv_1');
  });

  it('consumes exactly once', () => {
    const handoff = new AiHubHandoffService();
    handoff.requestOpen('conv_1');
    expect(handoff.consume()).toBe('conv_1');
    expect(handoff.pending()).toBeNull();
    expect(handoff.consume()).toBeNull();
  });

  it('allows opening without a conversation (null request stays clearable)', () => {
    const handoff = new AiHubHandoffService();
    handoff.requestOpen(null);
    expect(handoff.pending()).toBeNull();
    expect(handoff.consume()).toBeNull();
  });
});
