// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.MovementCountQuery
import com.openbank.aml.application.port.out.MovementCounterReader
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.ZoneOffset

/** The read side of `aml_movement_counter`: one SELECT, nothing else. */
@ApplicationScoped
class MovementCounterReaderImpl : MovementCounterReader {

    // The optional filters are spliced as fixed SQL fragments, never as values: every value is bound.
    override suspend fun countMovements(query: MovementCountQuery): Long = Panache.withSession {
        Panache.getSession().chain { session ->
            val selection = session.createNativeQuery(
                """
                SELECT coalesce(sum(movement_count), 0) FROM aml_movement_counter
                 WHERE party_id = :partyId AND currency = :currency AND band_code = :bandCode
                   AND scheme_revision = :schemeRevision
                   AND bucket_start >= :fromInclusive AND bucket_start < :toExclusive
                """.trimIndent() +
                    (if (query.direction != null) " AND direction = :direction" else "") +
                    (if (query.rail != null) " AND rail = :rail" else ""),
                BigDecimal::class.java,
            )
                .setParameter("partyId", query.partyId)
                .setParameter("currency", query.currency)
                .setParameter("bandCode", query.bandCode)
                .setParameter("schemeRevision", query.schemeRevision)
                .setParameter("fromInclusive", query.fromInclusive.atOffset(ZoneOffset.UTC))
                .setParameter("toExclusive", query.toExclusive.atOffset(ZoneOffset.UTC))
            query.direction?.let { selection.setParameter("direction", it.name) }
            query.rail?.let { selection.setParameter("rail", it) }
            selection.singleResult
        }
    }.awaitSuspending().toLong()
}
