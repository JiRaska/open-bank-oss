// SPDX-License-Identifier: Apache-2.0

package com.openbank.transaction.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.infrastructure.kafka.AccountBookingContext
import com.openbank.transaction.infrastructure.kafka.parseAccountBookingContext
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class PaymentBookingProjection(
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.transaction.booking-posting.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    internal suspend fun resolve(transaction: Transaction): AccountBookingContext? {
        if (!enabled) return null
        val accounts = listOfNotNull(transaction.sourceAccountId, transaction.targetAccountId).distinct()
        check(accounts.isNotEmpty()) { "Booking attribution requires at least one account" }
        val json = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(LOOKUP_SQL, String::class.java)
                    .setParameter("source", accounts.first())
                    .setParameter("target", accounts.last())
                    .setParameter("synthetic", transaction.synthetic)
                    .singleResult
            }
        }.awaitSuspending()
        val contexts = mapper.readTree(json)
        val booking = accounts.map { account ->
            val node = checkNotNull(contexts.get(account.toString())) { "Account booking projection is incomplete" }
            node.takeUnless { it.isNull }?.let(::parseAccountBookingContext)
        }
        return singleBookingContext(booking)
    }

    private companion object {
        const val LOOKUP_SQL = """
            SELECT CAST(COALESCE(jsonb_object_agg(account_id, booking_context), '{}'::jsonb) AS text)
            FROM account_booking_projection
            WHERE synthetic = :synthetic AND account_id IN (:source, :target)
        """
    }
}

internal fun singleBookingContext(contexts: List<AccountBookingContext?>): AccountBookingContext? {
    val first = contexts.first()
    check(contexts.all { it == first }) { "Payment crosses booking scopes and needs attributed clearing" }
    return first
}
