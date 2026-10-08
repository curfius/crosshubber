-- B6: user-curated chat session list — pin favorite conversations (rename reuses title).
ALTER TABLE ai_hub_conversations ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
