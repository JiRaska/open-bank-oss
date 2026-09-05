// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.libs.persistence.outbox.OutboxMessage

/**
 * Maps a domain [BalanceEvent] onto the transactional-outbox row (#8510).
 *
 * The payload is the SAME flat JSON the retired direct emitter produced
 * (`ObjectMapper.writeValueAsString(event)`), and `balance-outbox-out` publishes to the same
 * topic (`openbank.balance.events`), so consumers see the same bytes plus the additive
 * OutboxKafkaHeaders and a partition key — two publishers on one topic would race, and only one
 * of them can be atomic, which is why the direct emitter is gone rather than kept as a fallback.
 *
 * `aggregateId` is the account id: that is the dimension every consumer filters and joins on.
 */
fun BalanceEvent.toOutboxMessage(mapper: ObjectMapper): OutboxMessage = OutboxMessage(
    eventId = eventId,
    aggregateId = accountId,
    eventType = eventType.name,
    payload = mapper.writeValueAsString(this),
    createdAt = occurredAt.toInstant(),
)
