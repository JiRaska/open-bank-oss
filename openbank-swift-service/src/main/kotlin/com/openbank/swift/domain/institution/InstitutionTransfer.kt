// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.institution

import com.openbank.libs.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The rail a bank-own transfer settles on — chosen by treasury; owned by `openbank-libs-iso20022`. */
typealias InstitutionTransferRail = com.openbank.libs.iso20022.InstitutionTransferRail

/** Lifecycle of a bank-own transfer on this rail; owned by `openbank-libs-iso20022` (contract 9.2). */
typealias InstitutionTransferStatus = com.openbank.libs.iso20022.InstitutionTransferStatus

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
    /** pacs.009 `GrpHdr/MsgId`, fixed before the first submission and reused on any resend. */
    val messageId: String? = null,
    /** pacs.009 `GrpHdr/CreDtTm`, fixed with [messageId]. */
    val messageCreatedAt: Instant? = null,
    /** True once the single permitted resend after a typed "unknown to the scheme" was taken. */
    val resendTaken: Boolean = false,
) {
    val currency: String get() = amount.currency.code

    /** Minor units at the currency's own scale (the `amount_minor_units` column). */
    val amountMinorUnits: Long get() = amount.amount.unscaledValue().longValueExact()

    companion object {
        /** The pacs.009 / MT202 message type this rail reports on every status event. */
        const val MESSAGE_TYPE: String = "pacs.009"

        /** The deterministic pacs.009 `MsgId` of [t]: `FI-` + its 16-character MT202 reference. */
        fun messageIdFor(t: InstitutionTransfer): String = "FI-" + Mt202Mapper.reference(t)

        /**
         * A deterministic UUID v4-shaped UETR for [paymentId]: the name-based UUID of the id with
         * the version and variant bits forced to v4, so it satisfies the `UUIDv4Identifier`
         * pattern while staying stable across replays (a random one would change on every
         * rebuild). A tracking reference, not a secret: it carries no security property.
         */
        @Suppress("MagicNumber")
        fun uetrFor(paymentId: UUID): String {
            val named = UUID.nameUUIDFromBytes(("uetr:" + paymentId).toByteArray())
            val msb = (named.mostSignificantBits and 0xF000L.inv()) or 0x4000L
            val lsb = (named.leastSignificantBits and (0x3L shl 62).inv()) or (0x2L shl 62)
            return UUID(msb, lsb).toString()
        }
    }
}
