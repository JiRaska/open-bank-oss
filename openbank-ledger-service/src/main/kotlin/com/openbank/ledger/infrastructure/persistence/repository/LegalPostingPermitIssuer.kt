// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Trusted output of legal review and a funded reservation. Never accept it from a posting request. */
data class VerifiedDebitReservation(
    val accountId: UUID,
    val transactionId: UUID,
    val amount: Money,
    val revision: Long,
    val verificationRef: UUID,
    val actorPartyId: UUID,
    val reservationRef: UUID,
    val verificationExpiresAt: Instant,
    val reservationExpiresAt: Instant,
)

/** Internal publisher for the first scope: one net debit pocket per authorized journal. */
@ApplicationScoped
class LegalPostingPermitIssuer {
    /** Projects the monotonic revision of a verified legal snapshot into the posting boundary. */
    suspend fun applyRevision(accountId: UUID, currency: CurrencyCode, revision: Long): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Boolean>(
                    "SELECT ledger_apply_legal_pocket_revision(:pocket, :currency, :revision)",
                ).setParameter("pocket", accountId)
                    .setParameter("currency", currency.code)
                    .setParameter("revision", revision)
                    .singleResult
            }
        }.awaitSuspending()

    suspend fun issue(command: PostJournalCommand, reservation: VerifiedDebitReservation): Boolean {
        if (command.transactionId != reservation.transactionId || !covers(command, reservation)) return false
        // PostgreSQL stores microseconds. Round down so replay is exact and never extends authority.
        val expiry = minOf(reservation.verificationExpiresAt, reservation.reservationExpiresAt)
            .truncatedTo(ChronoUnit.MICROS)
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Boolean>(
                    """
                    SELECT ledger_publish_legal_posting_permit(
                        :pocket, :currency, :payment, :revision, CAST(CAST(:intent AS text) AS jsonb),
                        :expiry, :verification, :reservation, :actor
                    )
                    """.trimIndent(),
                )
                    .setParameter("pocket", reservation.accountId)
                    .setParameter("currency", reservation.amount.currency.code)
                    .setParameter("payment", reservation.transactionId)
                    .setParameter("revision", reservation.revision)
                    .setParameter("intent", LegalPostingFence.intent(command).encode())
                    .setParameter("expiry", expiry)
                    .setParameter("verification", reservation.verificationRef)
                    .setParameter("reservation", reservation.reservationRef)
                    .setParameter("actor", reservation.actorPartyId)
                    .singleResult
            }
        }.awaitSuspending()
    }

    private fun covers(command: PostJournalCommand, reservation: VerifiedDebitReservation): Boolean {
        if (!reservation.amount.isPositive() || reservation.revision <= 0) return false
        val pockets = command.lines.filter { it.subAccountId != null }
            .groupBy { it.subAccountId to it.baseCurrencyCode }
        val debits = pockets.mapValues { (_, lines) ->
            lines.sumOf { if (it.side == JournalSide.DEBIT) it.baseAmount else it.baseAmount.negate() }
        }.filterValues { it.signum() > 0 }
        val debit = debits[reservation.accountId to reservation.amount.currency.code] ?: return false
        return debits.size == 1 && debit.compareTo(reservation.amount.amount) == 0
    }
}
