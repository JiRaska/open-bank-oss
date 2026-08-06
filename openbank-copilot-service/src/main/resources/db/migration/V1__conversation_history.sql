-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

-- copilot conversation memory T1 (#3710): durable conversation history. Stores per-(customer,
-- conversation) message arrays; expires_at is updated on every append (rolling 90-day TTL).
--
-- RETENTION IS READ-SIDE ONLY IN THIS SLICE. PostgresConversationStore filters on
-- `expires_at > now()`, so an expired conversation stops being readable — but nothing DELETES
-- the row, so the message text stays on disk (and in every base backup) indefinitely. There is
-- no @Scheduled sweep and no PARTY_ERASED consumer in this service yet; both are required by
-- ADR-0238 and are tracked as follow-ups on #3710. Do not read the 90-day TTL as an erasure
-- guarantee until the sweep lands.
--
-- Rollback: DROP TABLE conversation_history;
CREATE TABLE conversation_history (
    id                  UUID                     NOT NULL PRIMARY KEY,
    customer_id         VARCHAR(255)             NOT NULL,
    conversation_id     VARCHAR(128)             NOT NULL,
    messages_json       TEXT                     NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_message_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (customer_id, conversation_id)
);

CREATE INDEX idx_conversation_history_lookup ON conversation_history (customer_id, conversation_id);
CREATE INDEX idx_conversation_history_expiry ON conversation_history (expires_at);
