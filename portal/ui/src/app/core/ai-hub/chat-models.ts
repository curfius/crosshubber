import type { LlmProviderConfig } from './ai-hub.service';
import type { AiHubDefaultModel } from './ai-hub-defaults';

export interface ChatModelOption {
  providerId: string;
  providerName: string;
  modelId: string;
  modelName: string;
  tokenId: string;
  tokenName: string;
}

export function modelKey(m: Pick<ChatModelOption, 'providerId' | 'modelId' | 'tokenId'>): string {
  return `${m.providerId}:${m.modelId}:${m.tokenId}`;
}

/** Resolves the default model from `ai-hub` settings, tolerating older blobs. */
export function matchChatModelOption(
  settings: Record<string, unknown>,
  options: ChatModelOption[],
): ChatModelOption | null {
  const dm = settings['defaultModel'];
  if (dm && typeof dm === 'object') {
    const sel = dm as Partial<AiHubDefaultModel>;
    const hit = options.find(
      (m) => m.providerId === sel.providerId && m.modelId === sel.modelId && m.tokenId === sel.tokenId,
    );
    if (hit) return hit;
  }
  const savedKey = settings['selectedModelKey'];
  if (typeof savedKey === 'string') {
    const hit = options.find((m) => modelKey(m) === savedKey);
    if (hit) return hit;
  }
  return options[0] ?? null;
}

export function toDefaultModel(m: ChatModelOption): AiHubDefaultModel {
  return { providerId: m.providerId, modelId: m.modelId, tokenId: m.tokenId };
}

export function deriveChatModelOptions(providers: LlmProviderConfig[], selectedTokenIds: ReadonlySet<string>): ChatModelOption[] {
  const result: ChatModelOption[] = [];
  for (const p of providers) {
    for (const t of p.tokens) {
      if (!selectedTokenIds.has(t.id)) continue;
      for (const m of t.models) {
        if (!m.enabled) continue;
        result.push({
          providerId: p.id,
          providerName: p.name,
          modelId: m.id,
          modelName: m.name,
          tokenId: t.id,
          tokenName: t.name,
        });
      }
    }
  }
  return result;
}
