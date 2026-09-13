// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

/**
 * Finding a company by NAME rather than by identifier (ADR-0284 D1, issue #9707).
 *
 * Onboarding opens on an identifier field, and a founder typically does not know their own IČO —
 * it lives on an invoice, not in anyone's head. Search closes that gap, and in doing so moves the
 * choice of legal entity from "the customer typed a number we then verified" to "the customer
 * picked a row". That is the whole risk of this feature: picking the wrong `STAVBY s.r.o.` out of
 * several hundred identically-named ones is silent, and the mistake only surfaces at signing or,
 * worse, at the first payment. Nothing here is allowed to bind a case on its own — a hit carries
 * an identifier, and the identifier still goes through the ordinary lookup + extract confirmation.
 */
data class RegistrySearchQuery(
    /** Free text matched against the registered company name. */
    val name: String,
    /**
     * Municipality, matched against the registered SEAT. Deliberately a narrowing hint rather than
     * a filter the caller must get right: for a large share of small companies the registered seat
     * is an accountant's office or a virtual address, so the town the founder associates with the
     * business is often not the town in the register.
     */
    val city: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /**
         * Shortest query accepted. Not cosmetic: a one- or two-letter query matches tens of
         * thousands of names, which the register answers with an error rather than a page (see
         * [RegistrySearchResult.tooManyMatches]), so it costs a round trip to learn nothing.
         */
        const val MIN_NAME_LENGTH = 3
    }
}

/**
 * One row a customer can pick. Carries what is needed to TELL TWO COMPANIES APART and nothing
 * else — the full extract (statutory body, representation rule, status) is fetched by identifier
 * once a row is chosen, so a search result can never be mistaken for a verified entity.
 */
data class RegistrySearchHit(
    val identifier: LegalEntityIdentifier,
    val name: String,
    /** Register legal-form code; the country pack turns it into a label and a [LegalFormClass]. */
    val legalFormCode: String?,
    /** The registered seat as the register renders it, town included. */
    val registeredAddress: String?,
)

/**
 * [totalMatches] is the register's own count, which can exceed `hits.size` — the customer needs to
 * know a narrower query exists, not just see the first twenty rows.
 *
 * [tooManyMatches] is a THIRD outcome next to hits and no-hits, and it is the common one for a
 * short query: measured against ARES on 2026-09-11, `obchodniJmeno=stavby` matches 2 818 subjects
 * and the register answers `VYSTUP_PRILIS_MNOHO_VYSLEDKU` — an error document, not a truncated
 * list, and paging does not help because the cap is on the match count rather than the page. It is
 * not an outage, so it must not be reported as one: the customer is told to add a town or more of
 * the name.
 */
data class RegistrySearchResult(
    val hits: List<RegistrySearchHit>,
    val totalMatches: Int,
    val tooManyMatches: Boolean = false,
) {
    companion object {
        fun tooMany(total: Int) = RegistrySearchResult(emptyList(), total, tooManyMatches = true)
        val EMPTY = RegistrySearchResult(emptyList(), 0)
    }
}
