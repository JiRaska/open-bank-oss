// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate

/**
 * The ONE lock every writer and the tie-out decision take for a day-close day: a transaction-scoped
 * advisory lock keyed on the business date. Reports, waivers and the CUTOFF -> TIED_OUT transition
 * all take it FIRST (before any row lock), so they serialise per day and cannot deadlock against
 * one another; the completeness a tie-out reads is the completeness the UPDATE commits against.
 */
internal object CloseParticipationLocks {

    /** Void-returning advisory lock wrapped so the driver gets a plain integer column back. */
    private const val LOCK_SQL = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:k))) AS l"

    fun lockDay(session: Mutiny.Session, businessDate: LocalDate): Uni<Any> = session.createNativeQuery<Any>(LOCK_SQL)
        .setParameter("k", "close-participation:$businessDate")
        .singleResult
}
