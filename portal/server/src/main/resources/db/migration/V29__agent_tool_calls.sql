-- Agent tool-call audit trail (AI plan B5, AI_MODULES_PLAN P2). One row per dispatch attempt;
-- insert-only, no updates. Occurred-at is stamped by the entity's @PrePersist.
CREATE TABLE agent_tool_calls (
  id             BIGSERIAL PRIMARY KEY,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id        TEXT        NOT NULL,
  conversation_id TEXT,
  module_key     TEXT,
  tool_id        TEXT        NOT NULL,
  tool_name      TEXT        NOT NULL,
  args_summary   TEXT,
  outcome        TEXT        NOT NULL,
  duration_ms    INTEGER
);

CREATE INDEX idx_agent_tool_calls_user ON agent_tool_calls (user_id, occurred_at DESC);
CREATE INDEX idx_agent_tool_calls_conversation ON agent_tool_calls (conversation_id);
