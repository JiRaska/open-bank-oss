-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

-- ADR-0238 T1: durable conversation history (#3710). Stores per-(customer, conversation) message
-- arrays; expires_at is updated on every append (rolling TTL). A scheduled sweep removes expired
-- rows (no separate JVM overhead — standard Flyway, the cleanup rides the existing Temporal
-- scheduler pattern or a lightweight Quarkus @Scheduled job). Rollback: DROP TABLE conversation_history;
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
