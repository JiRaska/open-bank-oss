// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.BalanceEventPublisher
import com.openbank.balance.application.port.out.BalanceOutboxRepository
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.infrastructure.persistence.repository.toOutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

/**
 * Transactional-outbox-backed [BalanceEventPublisher] (#8510) — the replacement for the retired
 * direct `@Channel("balance-events-out")` emitter.
 *
 * The event is persisted to `balance_outbox` in its own transaction and the dispatcher relays it
 * to the SAME topic (`openbank.balance.events`) with the SAME payload bytes the direct emitter
 * produced, so consumers see no difference beyond the additive OutboxKafkaHeaders and a partition
 * key. The only caller left on this port is the value-date roll's announcement (no state change
 * to compose with); every state-changing path writes its outbox row inside the mutation's own
 * transaction via the repository layer instead.
 */
@ApplicationScoped
class OutboxBalanceEventPublisher(private val outboxRepo: BalanceOutboxRepository, private val mapper: ObjectMapper) :
    BalanceEventPublisher {

    override suspend fun publish(event: BalanceEvent) {
        Panache.withTransaction {
            outboxRepo.persistInTransaction(event.toOutboxMessage(mapper))
        }.awaitSuspending()
    }
}
