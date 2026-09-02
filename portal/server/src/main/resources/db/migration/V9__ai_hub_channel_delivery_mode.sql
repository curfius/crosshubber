ALTER TABLE ai_hub_channels
  ADD COLUMN IF NOT EXISTS delivery_mode TEXT NOT NULL DEFAULT 'webhook';

ALTER TABLE ai_hub_channels DROP CONSTRAINT IF EXISTS ai_hub_channels_delivery_mode_check;
ALTER TABLE ai_hub_channels
  ADD CONSTRAINT ai_hub_channels_delivery_mode_check CHECK (delivery_mode IN ('webhook','polling'));

ALTER TABLE ai_hub_channels DROP CONSTRAINT IF EXISTS ai_hub_channels_delivery_mode_platform_check;
ALTER TABLE ai_hub_channels
  ADD CONSTRAINT ai_hub_channels_delivery_mode_platform_check CHECK (type = 'telegram' OR delivery_mode = 'webhook');
