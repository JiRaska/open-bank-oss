// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import java.time.Duration
import java.time.Instant

/**
 * [SentOutboxRetention] for one outbox table: the [OutboxSql.purgeSent] statement executed on the
 * calling Vert.x context. A hand-rolled v1 Panache repository opts into the fleet retention job by
 * delegation — no method of its own, so no class grows past detekt's function budget:
 *
 * ```
 * class ScaOutboxRepositoryImpl(...) :
 *     ScaOutboxRepository,
 *     SentOutboxRetention by PanacheOutboxRetention(OutboxTableShape("sca_outbox")),
 *     PanacheRepository<ScaOutboxEntity>
 * ```
 *
 * The kernel base ([AbstractPanacheOutboxRepository]) runs the same statement through [purgeSent]
 * in the companion. Needs only `id`, `status` and `sent_at` on the table.
 */
class PanacheOutboxRetention(private val shape: OutboxTableShape) : SentOutboxRetention {

    /** `sca_outbox` -> `sca`, `sepa_payment_outbox` -> `sepa-payment`: the table names the outbox. */
    override val retentionLabel: String = shape.table.removeSuffix("_outbox").replace('_', '-')

    override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int =
        purgeSent(shape, olderThan, batch, now)

    companion object {
        suspend fun purgeSent(shape: OutboxTableShape, olderThan: Duration, batch: Int, now: Instant): Int =
            Panache.withTransaction {
                Panache.getSession().chain { s ->
                    s.createNativeQuery<Int>(OutboxSql.purgeSent(shape))
                        .setParameter("sent", OutboxStatus.SENT.name)
                        .setParameter("cut", now.minus(olderThan))
                        .setParameter("limit", batch.coerceAtLeast(1))
                        .executeUpdate()
                }
            }.awaitSuspending()
    }
}
