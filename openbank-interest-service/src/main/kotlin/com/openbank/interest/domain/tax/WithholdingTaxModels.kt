// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Czech withholding-tax domain types for credit-interest taxation (ADR-0033).
 *
 * Framework-free: this package implements §36 / §38d zákona č. 586/1992 Sb. as a pure policy and
 * therefore carries **zero** infrastructure imports (ADR-0002 domain-layer rule).
 */

/** How a credit's interest is treated for withholding at capitalization (ADR-0033 §B/§E). */
enum class WithholdingTreatment {
    /** Tax withheld at source; customer credited net (resident individual 15 % / non-resident). */
    WITHHELD,

    /** Legal-entity beneficiary — interest enters the CIT base gross, no withholding (§36 scope). */
    NOT_WITHHELD,

    /** A statutory/treaty exemption with evidence on file — credited gross, reason recorded. */
    EXEMPT,

    /** Non-CZK interest — withholding deferred pending §38 ČNB conversion (ADR-0033 §E). */
    DEFERRED_FX
}

/** Tax residency of the beneficial owner. Drives the default non-resident rate path. */
enum class TaxResidency { RESIDENT, NON_RESIDENT }

/** Taxpayer type — individuals are withheld at source; legal entities are not (§36). */
enum class TaxpayerType { INDIVIDUAL, LEGAL_ENTITY }

/**
 * The tax attributes of the interest beneficiary, resolved via `TaxProfilePort` (ADR-0033 §C).
 *
 * @param treatyRate an applicable double-tax-treaty rate (e.g. 0.10) for a non-resident; when set it
 *   overrides the statutory default. `null` means "no treaty rate on file".
 * @param nonCooperatingState true if the beneficial owner resides in a non-cooperating / no-treaty
 *   state — triggers the 35 % rate (§36 odst. 1 písm. c) for a non-resident with no treaty rate.
 * @param exemptCode a statutory/treaty exemption code with evidence on file; when set the credit is
 *   [WithholdingTreatment.EXEMPT].
 */
data class TaxProfile(
    val taxpayerType: TaxpayerType,
    val residency: TaxResidency,
    val treatyRate: BigDecimal? = null,
    val nonCooperatingState: Boolean = false,
    val exemptCode: String? = null
) {
    companion object {
        /**
         * The fiscally conservative fallback used when the profile cannot be resolved (ADR-0033 §C):
         * a CZ-resident individual, withheld at 15 %. Never under-withholds.
         */
        val FAIL_SAFE_DEFAULT = TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.RESIDENT)
    }
}

/**
 * The outcome of [WithholdingTaxPolicy.compute]. `taxableBase` and `taxAmount` are whole CZK
 * (rounded down per daňový řád); `netAmount` is `gross − taxAmount` and preserves the gross scale.
 */
data class WithholdingResult(
    val taxableBase: BigDecimal,
    val rate: BigDecimal,
    val taxAmount: BigDecimal,
    val netAmount: BigDecimal,
    val treatment: WithholdingTreatment,
    val exemptCode: String? = null
)

/** Remittance lifecycle of a recorded withholding (ADR-0033 §D/§F). */
enum class WithholdingTaxStatus {
    /** Withheld at capitalization, not yet remitted (§38d). */
    RECORDED,

    /** Remitted to the tax office (monthly Vyúčtování daně vybírané srážkou) — set by reporting (G7). */
    REMITTED,

    /** Reconciled against the annual return — set by reporting (G7). */
    RECONCILED,

    /** The paired capitalization was reversed; this withholding is voided (ADR-0033 §F). */
    REVERSED
}

/**
 * The persisted withholding-tax liability paired with a capitalization (ADR-0033 §D). One row per
 * capitalization, recording the decision (even zero-tax treatments) for the audit trail and for the
 * downstream remittance/reporting capability (G7).
 *
 * @param partyRef reference to the beneficial-owner party (tax subject); `null` until account→party
 *   tax resolution lands (documented fast-follow).
 */
data class WithholdingTax(
    val id: UUID = UUID.randomUUID(),
    val capitalizationId: UUID,
    val accountId: UUID,
    val partyRef: String? = null,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val taxableBase: BigDecimal,
    val rate: BigDecimal,
    val taxAmount: BigDecimal,
    val currency: String,
    val treatment: WithholdingTreatment,
    val exemptCode: String? = null,
    val status: WithholdingTaxStatus = WithholdingTaxStatus.RECORDED,
    /** The remittance batch this record was folded into (ADR-0038); `null` until `REMITTED`. */
    val remittanceId: UUID? = null,
    val createdAt: OffsetDateTime,
)
