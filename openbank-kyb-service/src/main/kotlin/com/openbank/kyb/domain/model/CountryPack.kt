// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.LocalDate

/**
 * Everything jurisdiction-specific about legal-entity onboarding, as versioned effective-dated
 * DATA (ADR-0284 D2, the ADR-0212 compliance-pack shape): which identifier schemes the country
 * issues, which public register answers for them and what it lists, how beneficial owners are
 * established, how register legal-form codes classify, and which evidence each form needs.
 * A jurisdiction is a pack file plus, where its register has one, an adapter — never a branch.
 */
data class CountryPack(
    val country: String,
    val version: Int,
    val effectiveFrom: LocalDate,
    val displayName: Map<String, String>,
    val schemes: List<IdentifierScheme>,
    val registry: RegistryDescriptor,
    val uboRegister: UboRegisterDescriptor,
    /** Selects the free-text representation-rule parser (`cz`, …); null = no register text to parse. */
    val representationRuleParser: String?,
    val legalForms: Map<String, LegalFormClass>,
    val legalFormLabels: Map<String, Map<String, String>>,
    val requiredEvidence: Map<LegalFormClass, List<String>>,
    val amlLegalBasis: String?,
) {
    fun classify(legalFormCode: String?): LegalFormClass =
        legalFormCode?.trim()?.let { legalForms[it] } ?: LegalFormClass.OTHER

    fun isSoleTrader(legalFormCode: String?): Boolean = classify(legalFormCode) == LegalFormClass.SOLE_TRADER

    fun label(legalFormCode: String?, lang: String): String? = legalFormCode?.let { legalFormLabels[it]?.get(lang) }

    fun isEffectiveOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom)
}

data class RegistryDescriptor(
    val adapter: String?,
    val name: String,
    val publicSource: String?,
    val free: Boolean,
    val listsRepresentatives: Boolean,
    val listsRepresentationRule: Boolean,
)

/** How ultimate beneficial owners are established in this jurisdiction. */
data class UboRegisterDescriptor(
    val name: String?,
    val publicApi: Boolean,
    /** `SELF_DECLARATION` | `REGISTER_LOOKUP` | `OPERATOR_ATTESTATION` */
    val fallback: String,
    val threshold: Double,
    val legalBasis: String?,
)
