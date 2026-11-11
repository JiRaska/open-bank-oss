// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.security.MessageDigest
import java.time.Instant

/** Why a subject is on the case. A person who is both keeps the first role found; see [CaseScreening.subjectsOf]. */
enum class ScreeningSubjectKind { ENTITY, REPRESENTATIVE, BENEFICIAL_OWNER }

/**
 * The sanctions and terrorist-list outcome for one subject. [PENDING] is "no answer yet" — the
 * screen was never obtained — and is deliberately a value of its own: an outage must never be
 * stored as [CLEAR].
 */
enum class ScreeningVerdict { PENDING, CLEAR, POTENTIAL_HIT, HIT }

/**
 * One subject of the case's related-party screening and what the screen said about it.
 *
 * [pep] is a customer-risk fact recorded per subject. It raises the case to review but is never a
 * sanctions hit, so it lives beside [sanctions] rather than inside it.
 */
data class ScreeningSubject(
    /** Stable within the case: the idempotency key of the screen is derived from it, so a retry replays. */
    val key: String,
    val kind: ScreeningSubjectKind,
    val name: String,
    /** True for a legal person, false for a natural person. */
    val organisation: Boolean,
    val sanctions: ScreeningVerdict = ScreeningVerdict.PENDING,
    val pep: Boolean = false,
    val matchedName: String? = null,
    val screenedAt: Instant? = null,
) {
    val sanctionsHit: Boolean
        get() = sanctions == ScreeningVerdict.HIT || sanctions == ScreeningVerdict.POTENTIAL_HIT
}

/**
 * Approval was asked for while the related-party screening has no complete answer. Refused, never
 * waved through: the caller retries, and the retry screens again.
 */
class ScreeningPendingException :
    RuntimeException("compliance checks for this case have not completed yet; try again later")

/**
 * The sanctions / PEP screening of everyone the case is about: the entity, every listed
 * representative and every reportable beneficial owner.
 */
data class CaseScreening(val subjects: List<ScreeningSubject>) {

    /** Every subject has an answer. An empty subject list is not complete: nothing was screened. */
    val complete: Boolean
        get() = subjects.isNotEmpty() && subjects.none { it.sanctions == ScreeningVerdict.PENDING }

    val sanctionsHits: List<ScreeningSubject> get() = subjects.filter { it.sanctionsHit }

    val peps: List<ScreeningSubject> get() = subjects.filter { it.pep }

    /**
     * The review flag a screening finding raises. ONE neutral sentence, with no subject and no
     * outcome in it: the review reason is shown to the customer and published on the case events,
     * and telling a customer that a named person matched a sanctions list is tipping off. Who
     * matched what is in [subjects], which only an operator reads.
     */
    fun reviewFlags(): List<String> =
        if (sanctionsHits.isNotEmpty() || peps.isNotEmpty()) listOf(REVIEW_FLAG) else emptyList()

    /** Carries over the answers already obtained for subjects that are still on the case. */
    fun mergedInto(fresh: List<ScreeningSubject>): CaseScreening {
        val known = subjects.associateBy { it.key }
        return CaseScreening(fresh.map { known[it.key] ?: it })
    }

    companion object {
        const val REVIEW_FLAG = "related-party screening requires review"

        private const val KEY_HEX_CHARS = 24

        /**
         * The people and the entity to screen. A beneficial owner that is itself a company is
         * screened as an organisation; a person listed twice (a director who is also an owner)
         * is one subject, because the screen is of the person and not of the role.
         */
        fun subjectsOf(extract: RegistryExtract, owners: List<BeneficialOwner>): List<ScreeningSubject> {
            val all = buildList {
                add(subject(ScreeningSubjectKind.ENTITY, extract.legalName, organisation = !extract.isSoleTrader))
                extract.representatives.forEach {
                    add(subject(ScreeningSubjectKind.REPRESENTATIVE, it.fullName, organisation = false))
                }
                owners.forEach {
                    add(subject(ScreeningSubjectKind.BENEFICIAL_OWNER, it.fullName, organisation = it.corporate))
                }
            }
            return all.filter { it.name.isNotBlank() }.distinctBy { it.key }
        }

        private fun subject(kind: ScreeningSubjectKind, name: String, organisation: Boolean) =
            ScreeningSubject(key = keyOf(name, organisation), kind = kind, name = name.trim(), organisation = organisation)

        /**
         * Derived from the name as [IdentityMatch] reads it (titles, diacritics and word order
         * ignored), so the same person spelled two ways by two registers is still one subject, and
         * hashed so the key can travel in an idempotency header without carrying the name.
         */
        private fun keyOf(name: String, organisation: Boolean): String {
            val tokens = IdentityMatch.tokens(name).sorted().joinToString(" ").ifBlank { name.trim().lowercase() }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${if (organisation) "org" else "person"}|$tokens".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX_CHARS)
        }
    }
}
