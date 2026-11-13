// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

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
    /**
     * The opaque reference this subject is screened and monitored under: what the screen sends as
     * `subjectId` and what the monitored-subject registry is keyed by (ADR-0376 D1). A random
     * UUID, or the entity's party id — never derived from the name, because it travels in a URL
     * path and comes back on an event. Null on a subject screened before references existed.
     */
    val ref: String? = null,
    /** What ongoing monitoring reported about this subject after approval. Operators only. */
    val monitoringFindings: List<MonitoringMatch> = emptyList(),
    /** Decisions that a later monitoring finding reopened; kept because a decision is evidence. */
    val pastDispositions: List<PastDisposition> = emptyList(),
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

    /**
     * Ongoing monitoring matched this subject against a list entry. The finding is recorded once
     * per [MonitoringMatch.findingId] — a redelivered event returns the SAME instance — and it
     * always opens a decision: an earlier disposition was about an earlier match and is moved to
     * [pastDispositions], never reused. A PEP match sets the PEP fact and leaves the sanctions
     * verdict alone; every other kind, including one this service does not know, is read as a
     * sanctions-list match, which is the direction that cannot approve by mistake.
     */
    fun monitoringMatched(match: MonitoringMatch): ScreeningSubject {
        if (monitoringFindings.any { it.findingId == match.findingId }) return this
        val decided = disposition?.takeIf { it != FindingDisposition.OPEN }?.let {
            PastDisposition(it, disposedBy, dispositionReason, disposedAt)
        }
        return copy(
            sanctions = if (match.pep) sanctions else worseOf(sanctions, match.verdict),
            pep = pep || match.pep,
            monitoringFindings = monitoringFindings + match,
            pastDispositions = pastDispositions + listOfNotNull(decided),
            disposition = FindingDisposition.OPEN,
            disposedBy = null,
            dispositionReason = null,
            disposedAt = null,
        )
    }

    private fun worseOf(a: ScreeningVerdict, b: ScreeningVerdict): ScreeningVerdict =
        if (a == ScreeningVerdict.HIT || b == ScreeningVerdict.HIT) ScreeningVerdict.HIT else b
}

/**
 * One finding of ongoing monitoring (ADR-0376 D6) as kyb keeps it: which list entry, how strongly,
 * and how it was detected. It carries no name. [matchKind], [listType] and [detection] are the
 * producer's vocabulary kept verbatim as text: a value added upstream must still open a review
 * here, so nothing in this service enumerates them.
 */
data class MonitoringMatch(
    val findingId: UUID,
    val matchKind: String,
    val listType: String,
    val listEntryRef: String,
    val listEntryVersion: String,
    val matchScore: Double,
    val matchStatus: String,
    val detection: String,
    val receivedAt: Instant,
) {
    val pep: Boolean get() = matchKind == PEP_KIND

    /** Only an explicit `HIT` is a hit; anything else, known or not, is a potential one. */
    val verdict: ScreeningVerdict
        get() = if (matchStatus == HIT_STATUS) ScreeningVerdict.HIT else ScreeningVerdict.POTENTIAL_HIT

    private companion object {
        const val PEP_KIND = "PEP"
        const val HIT_STATUS = "HIT"
    }
}

/** A disposition that a later monitoring finding reopened. */
data class PastDisposition(
    val disposition: FindingDisposition,
    val disposedBy: String?,
    val reason: String?,
    val disposedAt: Instant?,
)

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
     * A human must look before the entity is activated — true for a case that has EVER had a
     * finding, whatever was decided about it. A decided finding still means the case is approved
     * by an operator's review and never by the last signature alone. Deliberately a boolean and not a reason:
     * nothing a screen found may be written where the applicant or an event consumer reads it —
     * naming the cause, even as "screening", is tipping off. Who matched what is in [subjects],
     * which only an operator reads.
     */
    val requiresReview: Boolean get() = subjects.any { it.finding }

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

    /** The current subjects that carry a reference: the set ongoing monitoring is registered for. */
    val monitorable: List<ScreeningSubject> get() = subjects.filter { it.current && it.ref != null }

    /**
     * Gives every current subject that has none its opaque reference. The entity takes
     * [entityRef] (its party id, so an analyst's clearance follows the party) when there is one;
     * everyone else takes a value from [mint], which must be random and must not be derived from
     * anything about the person. A reference is never changed once given.
     */
    fun withRefs(entityRef: String?, mint: () -> String): CaseScreening {
        if (subjects.none { it.current && it.ref == null }) return this
        return copy(
            subjects = subjects.map {
                when {
                    !it.current || it.ref != null -> it
                    it.kind == ScreeningSubjectKind.ENTITY && entityRef != null -> it.copy(ref = entityRef)
                    else -> it.copy(ref = mint())
                }
            },
        )
    }

    /**
     * Records a monitoring finding on the subject registered under [subjectRef]. Returns the SAME
     * instance when that finding is already recorded, and null when no current subject carries the
     * reference — the caller must tell the two apart.
     */
    fun monitoringMatched(subjectRef: String, match: MonitoringMatch): CaseScreening? {
        val subject = subjects.firstOrNull { it.current && it.ref == subjectRef } ?: return null
        val next = subject.monitoringMatched(match)
        if (next === subject) return this
        return copy(subjects = subjects.map { if (it === subject) next else it })
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
        require(reason.trim().length >= MIN_REASON) {
            "a disposition needs a reason of at least $MIN_REASON characters"
        }
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

        private fun subject(kind: ScreeningSubjectKind, name: String, organisation: Boolean) = ScreeningSubject(
            key = keyOf(name, organisation),
            kind = kind,
            name = name.trim(),
            organisation = organisation,
        )

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
