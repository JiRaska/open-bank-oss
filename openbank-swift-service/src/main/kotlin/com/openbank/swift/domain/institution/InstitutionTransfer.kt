// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.institution

import com.openbank.libs.domain.money.Money
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The rail a bank-own transfer settles on — chosen by treasury from the settlement instructions. */
enum class InstitutionTransferRail { CERTIS, TARGET2, SWIFT }

/**
 * Lifecycle of a bank-own (financial-institution) credit transfer. ACCEPTED: validated and
 * stored, not yet on the wire. SENT: the scheme took it. SETTLED: the scheme confirmed settlement
 * (pacs.002 `ACSC`). REJECTED: refused, by this rail's validation or by the scheme.
 */
enum class InstitutionTransferStatus { ACCEPTED, SENT, SETTLED, REJECTED }

/** The debtor (this bank) or creditor institution: BIC plus the settlement account at it. */
data class InstitutionParty(val bic: String, val accountNumber: String)

/**
 * One bank-own credit transfer released by treasury and carried on this rail (contract
 * `treasury.payment.released.v1` in, `swift.institution-transfer.status.v1` out).
 *
 * Natural keys: [idempotencyKey] (treasury's, unique) and [paymentId] (unique) — a replayed release
 * resolves to this same row and never to a second message. [uetr] is derived from [paymentId]
 * ([uetrFor]) so a rebuilt message always carries the same tracking reference.
 */
data class InstitutionTransfer(
    val id: UUID,
    val paymentId: UUID,
    val idempotencyKey: String,
    val rail: InstitutionTransferRail,
    val amount: Money,
    val valueDate: LocalDate,
    val debtor: InstitutionParty,
    val debtorGlAccountingClass: String,
    val creditor: InstitutionParty,
    val creditorName: String,
    val intermediaryBic: String?,
    val remittanceInformation: String?,
    val dealIds: List<UUID>,
    val flowIds: List<UUID>,
    val releasedBy: String,
    val releasedAt: Instant,
    val synthetic: Boolean,
    val status: InstitutionTransferStatus,
    val uetr: String,
    val reasonCode: String?,
    val reason: String?,
    val rawPacs009: String?,
    val rawMt202: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
    /** The counterparty SSI version treasury took the creditor block from (optional). */
    val ssiVersion: Long? = null,
) {
    val currency: String get() = amount.currency.code

    /** Minor units at the currency's own scale (the `amount_minor_units` column). */
    val amountMinorUnits: Long get() = amount.amount.unscaledValue().longValueExact()

    companion object {
        /** The pacs.009 / MT202 message type this rail reports on every status event. */
        const val MESSAGE_TYPE: String = "pacs.009"

        /**
         * A deterministic UUID v4-shaped UETR for [paymentId]: SHA-256 of the id with the version
         * and variant bits forced, so it satisfies the `UUIDv4Identifier` pattern while staying
         * stable across replays (a random one would change on every rebuild).
         */
        @Suppress("MagicNumber")
        fun uetrFor(paymentId: UUID): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(paymentId.toString().toByteArray())
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x40).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            val buf = ByteBuffer.wrap(hash, 0, 16)
            return UUID(buf.long, buf.long).toString()
        }
    }
}
