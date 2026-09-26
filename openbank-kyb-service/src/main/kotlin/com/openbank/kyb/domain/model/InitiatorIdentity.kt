// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser

/**
 * Who the logged-in initiator verifiably IS, as party-service records them — never what the request
 * claims. Matching a register representative against this, instead of against a name typed into
 * the request, is what stops a customer opening a company account on someone else's behalf: before
 * this existed the initiator picked a representative by index and inherited that person's name.
 */
data class InitiatorIdentity(
    val legalName: String,
    val address: RegisteredAddress?,
    /** party-service `kycStatus == APPROVED`. A legal name nobody has verified is only a claim. */
    val verified: Boolean,
)

/** The initiator is not, verifiably, a person the register lists as able to act for the entity. Refused, not reviewed. */
class InitiatorIdentityMismatchException(message: String) : RuntimeException(message)

/** Pure comparison rules for [InitiatorIdentity] against a register [Representative]. */
object IdentityMatch {

    /** Academic and professional titles the Czech register prints around a name; never part of identity. */
    private val TITLES = setOf(
        "ing", "mgr", "bc", "mudr", "mvdr", "mddr", "judr", "phdr", "rndr", "paeddr", "pharmdr", "thdr",
        "thlic", "doc", "prof", "dr", "dipl", "phd", "csc", "drsc", "dis", "mba", "llm", "msc", "bsc",
    )

    private const val MIN_NAME_TOKENS = 2

    /** Folded, title-free name tokens: `Ing. Oldřich Vaněk, Ph.D.` → `[oldrich, vanek]`. */
    internal fun tokens(name: String): List<String> = CzechRepresentationRuleParser.fold(name)
        .replace(Regex("[.,]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() && it !in TITLES }

    /**
     * A statutory member is the same person when the name tokens are identical as a multiset —
     * diacritics, titles and word order ignored (`Vaněk Oldřich` is `Oldřich Vaněk`), nothing else.
     * A single-token name never matches: one surname is not an identity.
     */
    fun samePerson(registered: String, verified: String): Boolean {
        val reg = tokens(registered).sorted()
        return reg.size >= MIN_NAME_TOKENS && reg == tokens(verified).sorted()
    }

    /**
     * A sole trader's register name is their business name, which may carry a trade suffix
     * (`Jan Novák - Truhlářství`), so the person's every name token must appear in it.
     */
    fun soleTraderIs(businessName: String, verified: String): Boolean {
        val person = tokens(verified)
        return person.size >= MIN_NAME_TOKENS && tokens(businessName).toSet().containsAll(person)
    }

    /**
     * True only when BOTH addresses are known and disagree. An unknown address on either side is no
     * evidence either way, so it is not a conflict: absence is not mismatch. Postal code decides when
     * both carry one; the city only when a postal code is missing.
     */
    fun addressConflicts(registered: RegisteredAddress?, verified: RegisteredAddress?): Boolean {
        if (registered == null || verified == null) return false
        if (registered.countryCode.isNotBlank() &&
            verified.countryCode.isNotBlank() &&
            !registered.countryCode.equals(verified.countryCode, ignoreCase = true)
        ) {
            return true
        }
        val regPostal = registered.postalCode?.filter(Char::isDigit).orEmpty()
        val verPostal = verified.postalCode?.filter(Char::isDigit).orEmpty()
        if (regPostal.isNotEmpty() && verPostal.isNotEmpty()) return regPostal != verPostal
        val regCity = registered.city?.let(CzechRepresentationRuleParser::fold).orEmpty()
        val verCity = verified.city?.let(CzechRepresentationRuleParser::fold).orEmpty()
        return regCity.isNotEmpty() && verCity.isNotEmpty() && regCity != verCity
    }
}
