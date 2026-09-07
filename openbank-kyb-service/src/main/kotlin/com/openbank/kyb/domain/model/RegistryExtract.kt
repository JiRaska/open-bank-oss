// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Jurisdiction-neutral legal-form class. The register's own code (ARES `pravniForma`, GLEIF ELF
 * code, Companies House `type`) is kept verbatim in [RegistryExtract.legalFormCode]; this is what
 * the rest of the platform reasons about — a sole trader signs alone and gets a personal-style
 * profile, a company needs its statutory body.
 */
enum class LegalFormClass {
    SOLE_TRADER,
    LIMITED_COMPANY,
    JOINT_STOCK,
    PARTNERSHIP,
    COOPERATIVE,
    FOUNDATION,
    PUBLIC_BODY,
    BRANCH,
    OTHER,
}

enum class EntityStatus { ACTIVE, DISSOLVED, IN_LIQUIDATION, INSOLVENT, UNKNOWN }

/** How far the extract is trusted. Only [VERIFIED] may drive an automatic signer count. */
enum class ExtractVerification { VERIFIED, UNVERIFIED }

data class RegisteredAddress(val line1: String?, val city: String?, val postalCode: String?, val countryCode: String)

/** A person the register lists as able to act for the entity. Dates of birth come from the register itself. */
data class Representative(
    val fullName: String,
    val dateOfBirth: LocalDate?,
    /** The organ (statutory body) the person sits in, as the register names it — `jednatelé`, `představenstvo`, `directors`. */
    val body: String?,
    /** The function inside the body, as the register names it — `jednatel`, `předseda představenstva`, `director`. */
    val role: String?,
    val since: LocalDate?,
)

enum class RepresentationMode {
    /** Any single listed representative binds the entity. */
    SOLE,

    /** Every listed representative must sign. */
    JOINT_ALL,

    /** [RepresentationRule.requiredSigners] of the listed representatives must sign together. */
    JOINT_N,

    /** The register text could not be parsed; the case goes to manual review rather than guessing low. */
    UNKNOWN,
}

data class RepresentationRule(val mode: RepresentationMode, val requiredSigners: Int?, val sourceText: String?) {

    /** How many DISTINCT verified signatures the agreement needs, or null when a human must decide. */
    fun signaturesRequired(representativeCount: Int): Int? = when (mode) {
        RepresentationMode.SOLE -> 1
        RepresentationMode.JOINT_ALL -> representativeCount.coerceAtLeast(1)
        RepresentationMode.JOINT_N -> requiredSigners?.coerceIn(1, representativeCount.coerceAtLeast(1))
        RepresentationMode.UNKNOWN -> null
    }

    companion object {
        val SOLE = RepresentationRule(RepresentationMode.SOLE, 1, null)
        val UNKNOWN = RepresentationRule(RepresentationMode.UNKNOWN, null, null)
    }
}

/**
 * The normalised public-register record for one [LegalEntityIdentifier] (ADR-0284 D1). Every
 * adapter produces exactly this shape; nothing downstream sees a register's own field names.
 */
data class RegistryExtract(
    val identifier: LegalEntityIdentifier,
    val legalName: String,
    val legalFormCode: String?,
    val legalFormClass: LegalFormClass,
    val status: EntityStatus,
    val registeredAddress: RegisteredAddress?,
    val incorporatedOn: LocalDate?,
    val taxId: String?,
    val representatives: List<Representative>,
    val representationRule: RepresentationRule,
    /** Other identifiers the register knows for the same entity (an LEI on an ARES record, a national id on a GLEIF one). */
    val otherIdentifiers: Map<IdentifierScheme, String> = emptyMap(),
    val source: String,
    val sourceRef: String?,
    val verification: ExtractVerification,
    val fetchedAt: Instant,
) {
    val isSoleTrader: Boolean get() = legalFormClass == LegalFormClass.SOLE_TRADER
}
