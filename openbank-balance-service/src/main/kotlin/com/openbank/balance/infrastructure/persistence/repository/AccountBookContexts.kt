// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.infrastructure.persistence.repository

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import java.util.UUID

class MissingAccountBookingContext : RuntimeException("Authoritative account booking context is not projected yet")

class BookingContextConflict : IllegalArgumentException("account booking context does not match the event")

/** Wire boundary; the authoritative value is carried by AccountCreated, never by a movement. */
typealias AccountBookContext = com.openbank.libs.market.BookingContext

object AccountBookContexts {
    suspend fun record(account: UUID, context: AccountBookContext?) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Void>(INSERT)
                    .setParameter("account", account)
                    .setParameter("bank", context?.bankId)
                    .setParameter("establishment", context?.establishmentId)
                    .setParameter("country", context?.hostCountry)
                    .setParameter("mode", context?.mode)
                    .setParameter("synthetic", com.openbank.libs.messaging.SyntheticTaintKafkaRail.currentlyTainted())
                    .executeUpdate().flatMap {
                        session.createNativeQuery(READ_CONTEXT, String::class.java)
                            .setParameter("account", account).singleResult.map { stored ->
                                val expected = listOf(
                                    context?.bankId,
                                    context?.establishmentId,
                                    context?.hostCountry,
                                    context?.mode,
                                ).joinToString("|") { it?.toString() ?: "" }
                                if (stored != expected) throw BookingContextConflict()
                            }
                    }
            }
        }.awaitSuspending()
    }

    /** Called inside the projection transaction, before even a duplicate may release cover. */
    fun verify(account: UUID, branch: UUID?): Uni<Void> = Panache.getSession().flatMap { session ->
        session.createNativeQuery(
            "SELECT CASE WHEN mode = 'BRANCH' THEN establishment_id::text ELSE '' END " +
                "FROM account_booking_context WHERE account_id = :account FOR SHARE",
            String::class.java,
        ).setParameter("account", account).singleResultOrNull.map { expected ->
            if (expected == null && branch != null) {
                // Different Kafka topics may arrive out of order. Retry until AccountCreated lands.
                throw MissingAccountBookingContext()
            }
            if ((expected ?: "") != (branch?.toString() ?: "")) throw BookingContextConflict()
            null
        }
    }

    private val INSERT = """
        INSERT INTO account_booking_context(account_id,bank_id,establishment_id,host_country,mode,synthetic)
        VALUES (:account,CAST(:bank AS UUID),CAST(:establishment AS UUID),:country,:mode,:synthetic)
        ON CONFLICT(account_id) DO NOTHING
    """.trimIndent()
    private val READ_CONTEXT = """
        SELECT coalesce(bank_id::text,'') || '|' || coalesce(establishment_id::text,'') || '|' ||
            coalesce(host_country::text,'') || '|' || coalesce(mode,'')
        FROM account_booking_context WHERE account_id = :account
    """.trimIndent()
}
