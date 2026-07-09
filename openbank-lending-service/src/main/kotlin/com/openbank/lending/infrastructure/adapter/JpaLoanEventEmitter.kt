// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LendingOutboxRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.jboss.logging.Logger

/**
 * Real [LoanEventEmitter]: persists the [LendingOutboxMessage] to the `lending_outbox` table
 * (ADR-0003 transactional outbox), where [com.openbank.lending.infrastructure.outbox.LendingOutboxDispatcher]
 * later picks it up and publishes it via [com.openbank.lending.infrastructure.outbox.KafkaLendingOutboxEventPublisher].
 * The transaction boundary lives on [LendingOutboxRepository.persistInTransaction] itself
 * (`@WithTransaction` on [com.openbank.lending.infrastructure.persistence.repository.LendingOutboxRepositoryImpl]) —
 * this adapter is a thin, CDI-mockable delegate.
 *
 * Build-time gated by `lending.outbox.backend=jpa`; when unset the `@Default` no-op
 * (`LoggingLoanEventEmitter`) stays bound and events are only logged, never persisted — mirrors
 * [RestLedgerPostingAdapter]'s `lending.ledger.backend=rest` gate (the platform realization pattern,
 * ADR-0045). Unlike the ledger gate, this one is enabled by default (`application.yaml`): the write
 * targets this service's own Postgres schema, already a hard boot dependency (Flyway/Hibernate
 * Reactive), so there is no "offline build" story being protected here — only an explicit override
 * can fall back to the no-op.
 *
 * This opens its own transaction, matching this service's existing per-repository-call transaction
 * granularity (see `LoanRepositoryImpl`/`InstallmentRepositoryImpl`): it is not literally the same DB
 * transaction as the preceding aggregate save, since `emit()` is invoked from
 * [com.openbank.lending.application.usecase.LendingService] only after the aggregate write(s) and the
 * ledger posting have already committed. True single-transaction atomicity would require refactoring
 * those call sites to pass the outbox message into the aggregate repository itself (as e.g.
 * `openbank-transaction-service` does) — out of scope for wiring this missing adapter.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "lending.outbox.backend", stringValue = "jpa")
class JpaLoanEventEmitter(private val outbox: LendingOutboxRepository) : LoanEventEmitter {

    private val log = Logger.getLogger(JpaLoanEventEmitter::class.java)

    override fun emit(message: LendingOutboxMessage): Uni<Unit> = outbox.persistInTransaction(message).map {
        log.debugf("outbox write: %s for %s", message.eventType, message.aggregateId)
    }
}
