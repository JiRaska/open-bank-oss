// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import com.openbank.tax.domain.gsft.DepositAccount
import com.openbank.tax.domain.gsft.Depositor
import com.openbank.tax.domain.gsft.DepositorIdentity
import com.openbank.tax.domain.gsft.FxRates
import com.openbank.tax.domain.gsft.QualifyingCredit
import com.openbank.tax.domain.gsft.SpecialRegimeShare
import java.time.LocalDate

/**
 * The inputs of `InsuredClaims.assemble` and `GsftBatchRenderer.render` for one relevant day
 * (zákon č. 21/1992 Sb. § 41d, issue #15356).
 */
interface GsftSnapshotPort {
    suspend fun snapshot(relevantDay: LocalDate): GsftSnapshot
}

/**
 * One relevant day's depositor data, as far as a verified source exists for it.
 *
 * Every field the fleet cannot yet source is named in [gaps], never filled with a plausible
 * value: a snapshot with a non-empty [gaps] is not a filing, and [requireComplete] refuses it.
 * [rates] is null exactly when [GsftDataGap.FX_RATES] is open.
 */
data class GsftSnapshot(
    val relevantDay: LocalDate,
    val depositors: List<Depositor>,
    val accounts: List<DepositAccount>,
    val identities: List<DepositorIdentity>,
    val rates: FxRates?,
    val gaps: Set<GsftDataGap>,
    /** Beneficiary shares of special-regime accounts (DII); no owning service models them yet. */
    val specialShares: List<SpecialRegimeShare>,
    /** § 41e odst. 3 credits a depositor has claimed; claim-based, so empty until one is filed. */
    val qualifyingCredits: List<QualifyingCredit>,
) {
    val complete: Boolean get() = gaps.isEmpty()

    /** Refuses a snapshot that still carries an unsourced field — the GSFT delivery must not use it. */
    fun requireComplete(): GsftSnapshot {
        if (!complete) {
            throw ReturnDataUnavailableException(
                "GSFT snapshot for $relevantDay has unsourced data: ${gaps.sorted().joinToString()}",
            )
        }
        return this
    }
}

/** A field of the GSFT delivery with no verified upstream source (gsft-depositor-data.md, "Data sourcing"). */
enum class GsftDataGap {
    /** Roster is account-service's CURRENT ACTIVE list: dormant/frozen deposits and day-D state are missing. */
    ROSTER_ACTIVE_ONLY_CURRENT,

    /** account-service carries one partyId per account: joint holders and shares are unknown. */
    JOINT_HOLDERS,

    /** No accrued-interest read is admitted for this caller: claims are UNDERSTATED by the interest. */
    ACCRUED_INTEREST,

    /** No birth number / address read exists for this caller: no DepositorIdentity can be built. */
    DEPOSITOR_IDENTITY,

    /** No § 41b exclusion-category read exists: every depositor reads as eligible. */
    EXCLUSION_CATEGORY,

    /**
     * balance-service rewinds the current balance by projected movements after D; with no projection
     * events it silently answers the CURRENT balance, indistinguishable on the wire.
     */
    BALANCE_AS_OF_UNVERIFIED,

    /** No ČNB-fixing-for-a-day read is admitted for this caller. */
    FX_RATES,
}
