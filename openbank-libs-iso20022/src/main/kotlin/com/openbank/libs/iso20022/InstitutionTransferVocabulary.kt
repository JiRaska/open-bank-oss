// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

/*
 * The one owner of the bank-own (financial-institution) credit-transfer vocabulary (contract 9:
 * `treasury.payment.released.v1` / `swift.institution-transfer.status.v1`). Treasury chooses the
 * rail, the swift rail carries the pacs.009 and the clearing simulator stands in for the schemes;
 * all three use these types, so a value added here is added everywhere at once
 * (rules.yaml: enum_reference_data). The enum NAMES are the wire strings and the persisted values.
 */

/**
 * The rail a bank-own credit transfer (pacs.009) travels on. [currency] is the only currency the
 * rail clears (`null` = any: the correspondent route), [clearingSystem] the `GrpHdr/SttlmInf/ClrSys`
 * code a pacs.009 on that rail carries (`null` = none: correspondent banking).
 */
enum class InstitutionTransferRail {
    /** The domestic CZK RTGS. */
    CERTIS,

    /** The euro RTGS. */
    TARGET2,

    /** Correspondent banking over the FI messaging network — any currency, no clearing system. */
    SWIFT,
    ;

    val currency: String?
        get() = when (this) {
            CERTIS -> "CZK"
            TARGET2 -> "EUR"
            SWIFT -> null
        }

    val clearingSystem: String?
        get() = when (this) {
            CERTIS -> "CERTIS"
            TARGET2 -> "TGT"
            SWIFT -> null
        }
}

/**
 * How a counterparty's standing settlement instruction (SSI) says it is paid. A different concept
 * from [InstitutionTransferRail]: an SSI may name SEPA, the customer-scheme credit transfer, which is
 * never a bank-own rail — a bank-own payment to a SEPA SSI travels TARGET2 ([institutionRail]).
 */
enum class SettlementRail {
    CERTIS,
    SEPA,
    TARGET2,
    SWIFT,
    ;

    val institutionRail: InstitutionTransferRail
        get() = when (this) {
            CERTIS -> InstitutionTransferRail.CERTIS
            SEPA, TARGET2 -> InstitutionTransferRail.TARGET2
            SWIFT -> InstitutionTransferRail.SWIFT
        }
}

/**
 * Lifecycle of a bank-own credit transfer on its rail. Only [ANNOUNCED] statuses are published
 * (contract 9.2) and may reach a consumer; the others are the rail's internal states. ACCEPTED:
 * validated and stored, not yet on the wire. SENT: the scheme took it. SETTLED: the scheme
 * confirmed settlement (pacs.002 `ACSC`). REJECTED: refused, by the rail's validation or the scheme.
 */
enum class InstitutionTransferStatus {
    ACCEPTED,

    /**
     * Internal, never announced: the pacs.009 identity (MsgId, UETR, CreDtTm) is persisted and the
     * submission is in flight. A crash here is resolved by querying the scheme, not by resending.
     */
    SUBMITTING,

    /** Internal, never announced: no readable answer; resolved by a status query by UETR. */
    SUBMISSION_UNKNOWN,

    /**
     * Internal, terminal for automation: the scheme's answer about this transfer was ambiguous (a
     * bare 404, a status for another message, or still unknown after the one permitted resend).
     * Nothing is resent; a human reconciles against the scheme. Counted and alerted.
     */
    MANUAL_RECONCILIATION,
    SENT,
    SETTLED,
    REJECTED,
    ;

    /** True for a status contract 9.2 publishes. */
    val announced: Boolean get() = this in ANNOUNCED

    companion object {
        /** The statuses contract 9.2 publishes; the others are internal and never leave the rail. */
        val ANNOUNCED: Set<InstitutionTransferStatus> = setOf(ACCEPTED, SENT, SETTLED, REJECTED)
    }
}
