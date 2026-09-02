import type { LlmProviderConfig } from './ai-hub.service';

export interface ChatModelOption {
  providerId: string;
  providerName: string;
  modelId: string;
  modelName: string;
  tokenId: string;
  tokenName: string;
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
