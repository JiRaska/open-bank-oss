// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Where a beneficial-owner statement came from. This is NOT a quality score — it is the question an
 * auditor asks first, and the three answers need different work from the bank.
 */
enum class UboSource {
    /** A public beneficial-ownership register answered. */
    REGISTER,

    /** The jurisdiction has no public API (or none we may use), so the customer must declare. */
    SELF_DECLARATION,

    /**
     * We could not check: the register was unreachable, or this jurisdiction has no country pack at
     * all. Deliberately distinct from [SELF_DECLARATION] — "nobody has told us" and "we could not
     * check" look identical in a report that merges them, and only one of the two is fixed by
     * asking the customer. Both leave `requiresDeclaration` true, so neither lets a case proceed
     * as though ownership were established.
     */
    UNAVAILABLE,
}

/**
 * How much of the entity a person controls, as the register states it.
 *
 * A BAND, never a number. The UK PSC register publishes `ownership-of-shares-25-to-50-percent`
 * and nothing finer; turning that into `37.5%` invents a precision no register issued, and the
 * invented figure is what a downstream threshold test would then compare against.
 */
enum class OwnershipBand {
    /** Below the AMLD5 25% threshold, or a control that is not an ownership share at all. */
    BELOW_THRESHOLD,
    PCT_25_TO_50,
    PCT_50_TO_75,
    PCT_75_TO_100,

    /** The register names the person as controlling but does not quantify it. */
    UNQUANTIFIED,
}

/**
 * One person or entity the register names as a beneficial owner.
 *
 * [natureOfControl] is carried VERBATIM from the register (`voting-rights-25-to-50-percent`,
 * `right-to-appoint-and-remove-directors`, …). It is evidence, and a paraphrase is not: an
 * analyst deciding whether a control is ownership or influence needs the register's own words.
 */
data class BeneficialOwner(
    val fullName: String,
    /** Null where the register publishes only a partial date (Companies House gives month + year). */
    val dateOfBirth: LocalDate?,
    val nationality: String?,
    val countryOfResidence: String?,
    val band: OwnershipBand,
    val natureOfControl: List<String>,
    val notifiedOn: LocalDate?,
    /** True when the "owner" is itself a company or a legal person, so the chain does not end here. */
    val corporate: Boolean,
)

/**
 * What is known about an entity's beneficial owners at one moment.
 *
 * [owners] being empty is not the same as [UboSource.UNAVAILABLE]. A register CAN legitimately hold
 * no PSC — a company can state that it has none, or that it has not yet identified one — and that
 * statement is itself a finding an analyst must see, which is why [registerStatements] is carried
 * separately rather than collapsed into an empty list.
 */
data class UboFinding(
    val identifier: LegalEntityIdentifier,
    val source: UboSource,
    val owners: List<BeneficialOwner>,
    /** Register statements such as "no individual PSC identified" — an answer, not an absence. */
    val registerStatements: List<String>,
    /** The jurisdiction's ownership threshold from the country pack (AMLD5 default is 0.25). */
    val threshold: Double,
    val registerName: String?,
    val sourceRef: String?,
    val fetchedAt: Instant,
) {
    /**
     * True when the bank still has to ask a human. Both an unreachable register and a jurisdiction
     * with no public API land here, and the case's evidence requirement is what distinguishes them.
     */
    val requiresDeclaration: Boolean get() = source != UboSource.REGISTER

    /** Owners at or above the jurisdiction threshold. An unquantified control counts — it is not evidence of a small stake. */
    val reportableOwners: List<BeneficialOwner> get() = owners.filter { it.band != OwnershipBand.BELOW_THRESHOLD }
}
