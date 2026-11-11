// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.security.MessageDigest
import java.time.Instant

/** Why a subject is on the case. A person who is both keeps the first role found; see [CaseScreening.subjectsOf]. */
enum class ScreeningSubjectKind {
    ENTITY,
    REPRESENTATIVE,

    /** A beneficial owner the ownership register reports. */
    BENEFICIAL_OWNER,

    /** A person the customer named in the declarations who is on neither register. */
    DECLARED_PERSON,
}

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
    /**
     * False once the subject is no longer among the case's parties. Such a subject is kept only
     * while it carries a finding: a hit does not go away because the person was taken off a form.
     */
    val current: Boolean = true,
    /** Null while there is nothing to decide (pending, or clear and not a PEP). */
    val disposition: FindingDisposition? = null,
    val disposedBy: String? = null,
    val dispositionReason: String? = null,
    val disposedAt: Instant? = null,
) {
    val sanctionsHit: Boolean
        get() = sanctions == ScreeningVerdict.HIT || sanctions == ScreeningVerdict.POTENTIAL_HIT

    /** Anything a human has to decide: a sanctions hit, a potential hit or a PEP match. */
    val finding: Boolean get() = sanctionsHit || pep

    /** A finding nobody has decided. A finding stored without a disposition counts as open. */
    val findingOpen: Boolean get() = finding && (disposition == null || disposition == FindingDisposition.OPEN)

    /** An operator confirmed the sanctions match is this subject. Nothing approves past it. */
    val sanctionsConfirmed: Boolean get() = sanctionsHit && disposition == FindingDisposition.CONFIRMED

    /** The screen's answer. A non-clear answer opens a finding; it is never decided by arriving. */
    fun answered(verdict: ScreeningVerdict, pep: Boolean, matchedName: String?, at: Instant): ScreeningSubject {
        val next = copy(sanctions = verdict, pep = pep, matchedName = matchedName, screenedAt = at)
        return if (next.finding) next.copy(disposition = FindingDisposition.OPEN) else next
    }
}

/**
 * What an operator decided about one finding. There is no implicit way out of [OPEN]: resolving a
 * review, collecting a signature or removing the person from the case all leave it open.
 */
enum class FindingDisposition {
    OPEN,

    /** The match is not this subject. */
    CLEARED_FALSE_POSITIVE,

    /** The match is this subject. For a sanctions finding the case can then only be rejected. */
    CONFIRMED,
}

/**
 * An approval was attempted while a finding is undecided, or after a sanctions finding was
 * confirmed. Thrown on OPERATOR routes only — the applicant's routes never raise it, because its
 * existence says what a screen found.
 */
class ScreeningFindingException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        const val OPEN = "SCREENING_FINDING_OPEN"
        const val CONFIRMED = "SCREENING_FINDING_CONFIRMED"
    }
}

/**
 * Approval was asked for while the related-party screening has no complete answer. Refused, never
 * waved through: the caller retries, and the retry screens again.
 */
class ScreeningPendingException :
    RuntimeException("compliance checks for this case have not completed yet; try again later")

/**
 * The sanctions / PEP screening of everyone the case is about: the entity, every listed
 * representative, every reportable beneficial owner and every person the customer declared.
 *
 * [ownerSource] is where the beneficial owners in [subjects] came from when the list was last
 * built. It is part of the answer: with [UboSource.UNAVAILABLE] nobody could say who owns the
 * entity, so the list may be short and "everyone on it is clear" proves nothing.
 */
data class CaseScreening(val subjects: List<ScreeningSubject>, val ownerSource: UboSource? = null) {

    /**
     * Every subject has an answer AND the owners among them were actually established. An empty
     * list is not complete (nothing was screened), and neither is a list built while the ownership
     * lookup could not be made.
     *
     * This says nothing about people who joined the case AFTER the list was built — the use case
     * rebuilds the list from the case's current parties before every approval for that reason.
     */
    val complete: Boolean
        get() = ownerSource != null &&
            ownerSource != UboSource.UNAVAILABLE &&
            subjects.any { it.current } &&
            subjects.none { it.current && it.sanctions == ScreeningVerdict.PENDING }

    /** Findings nobody has decided — on current subjects AND on subjects that have since left. */
    val openFindings: List<ScreeningSubject> get() = subjects.filter { it.findingOpen }

    val confirmedSanctions: List<ScreeningSubject> get() = subjects.filter { it.sanctionsConfirmed }

    /** Nothing here stands in the way of approval: complete, every finding decided, no confirmed sanctions match. */
    val approvable: Boolean get() = complete && openFindings.isEmpty() && confirmedSanctions.isEmpty()

    val sanctionsHits: List<ScreeningSubject> get() = subjects.filter { it.sanctionsHit }

    val peps: List<ScreeningSubject> get() = subjects.filter { it.pep }

    /**
     * A human must look before the entity is activated. Deliberately a boolean and not a reason:
     * nothing a screen found may be written where the applicant or an event consumer reads it —
     * naming the cause, even as "screening", is tipping off. Who matched what is in [subjects],
     * which only an operator reads.
     */
    val requiresReview: Boolean get() = openFindings.isNotEmpty() || confirmedSanctions.isNotEmpty()

    /**
     * [fresh] is the case's CURRENT subject list. Answers already obtained for people still on it
     * are kept. A subject that LEFT is dropped only if it was clear or never answered; one that
     * carries a finding stays, marked no longer current, and keeps counting until an operator
     * decides it — otherwise taking a listed person off the declarations would approve the case.
     */
    fun rebuilt(fresh: List<ScreeningSubject>, ownerSource: UboSource): CaseScreening {
        val known = subjects.associateBy { it.key }
        val freshKeys = fresh.map { it.key }.toSet()
        val now = fresh.map { known[it.key]?.copy(kind = it.kind, name = it.name, current = true) ?: it }
        val leftWithFinding = subjects.filter { it.key !in freshKeys && it.finding }.map { it.copy(current = false) }
        return CaseScreening(now + leftWithFinding, ownerSource)
    }

    /**
     * An operator decides one finding. Only an OPEN finding can be decided, and a decision is not
     * revised here: a second opinion is a new screen, not an edit of the first.
     */
    fun disposed(
        subjectKey: String,
        disposition: FindingDisposition,
        operator: String,
        reason: String,
        at: Instant,
    ): CaseScreening {
        require(disposition != FindingDisposition.OPEN) { "a disposition must be CLEARED_FALSE_POSITIVE or CONFIRMED" }
        require(operator.isNotBlank()) { "the deciding operator must be identified" }
        require(reason.trim().length >= MIN_REASON) { "a disposition needs a reason of at least $MIN_REASON characters" }
        val subject = subjects.firstOrNull { it.key == subjectKey }
            ?: throw IllegalArgumentException("no screened subject '$subjectKey' on this case")
        if (!subject.findingOpen) throw CaseTransitionException("subject '$subjectKey' has no open finding")
        return copy(
            subjects = subjects.map {
                if (it.key != subjectKey) {
                    it
                } else {
                    it.copy(
                        disposition = disposition,
                        disposedBy = operator,
                        dispositionReason = reason.trim(),
                        disposedAt = at,
                    )
                }
            },
        )
    }

    companion object {
        private const val KEY_HEX_CHARS = 24
        const val MIN_REASON = 10

        /**
         * The people and the entity to screen. A beneficial owner that is itself a company is
         * screened as an organisation; a person listed twice (a director who is also an owner)
         * is one subject, because the screen is of the person and not of the role.
         */
        fun subjectsOf(
            extract: RegistryExtract,
            owners: List<BeneficialOwner>,
            declaredPersons: List<String> = emptyList(),
        ): List<ScreeningSubject> {
            val all = buildList {
                add(subject(ScreeningSubjectKind.ENTITY, extract.legalName, organisation = !extract.isSoleTrader))
                extract.representatives.forEach {
                    add(subject(ScreeningSubjectKind.REPRESENTATIVE, it.fullName, organisation = false))
                }
                owners.forEach {
                    add(subject(ScreeningSubjectKind.BENEFICIAL_OWNER, it.fullName, organisation = it.corporate))
                }
                // Where the register has no owners to report (no public register, or the customer
                // corrected it), the people the customer named are the only owners anyone knows of.
                declaredPersons.forEach {
                    add(subject(ScreeningSubjectKind.DECLARED_PERSON, it, organisation = false))
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
