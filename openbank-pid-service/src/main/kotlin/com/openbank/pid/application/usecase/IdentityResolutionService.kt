// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.usecase

import com.openbank.libs.identity.BlindIndex
import com.openbank.libs.identity.MatchKey
import com.openbank.libs.identity.RodneCislo
import com.openbank.pid.application.port.`in`.*
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.identity.IdentityAttributes
import com.openbank.pid.domain.identity.MatchBand
import com.openbank.pid.domain.identity.ProbabilisticMatcher
import com.openbank.pid.domain.model.ApplicantSnapshot
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.Gender
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.VerificationTrigger
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.UUID

/**
 * Three-tier identity resolution before party creation (ADR-0072).
 *
 * **Tier 1 — Deterministic RČ blind index** (when [birthNumberRaw] is supplied and valid):
 *   HMAC-SHA256 the canonical RČ with the service pepper → equality lookup against
 *   `party_external_ids(BIRTH_NUMBER, <hex>)`.
 *   - Match + matching core attributes → [ResolutionResult.MatchExisting]
 *   - Match + divergent attributes (possible collision / data-entry error) →
 *     [ResolutionResult.NeedsManualVerification] with trigger RN_COLLISION
 *   - No match → fall through to record [ResolutionResult.NoMatch]
 *     (the blind index is stored at party-creation time via `createParty`)
 *
 * **Tier 2 — Normalized candidate match** (no RČ, or invalid/unparseable RČ):
 *   Compute `MatchKey.of(familyName, givenName, birthdate, birthplace)` and query the
 *   `(family_name, birthdate)` index.  One or more candidates →
 *   [ResolutionResult.NeedsManualVerification] with trigger NAMESAKE_CANDIDATE.
 *   Zero candidates → [ResolutionResult.NoMatch].
 *
 * **Pepper availability**: if `openbank.pid.birth-number-pepper` is blank/absent (e.g. in CI
 * without a live Vault), tier 1 is skipped with a WARN and tier 2 runs.  The service still
 * starts; the blind-index feature activates only when the pepper is injected.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // cohesive three-tier resolver: tier-1/2/2′ + adjudication + small helpers
class IdentityResolutionService(
    private val partyRepository: PartyRepository,
    private val adjudication: IdentityAdjudicationUseCase,
    @ConfigProperty(name = "openbank.pid.birth-number-pepper")
    private val pepperHex: Optional<String>,
) : ResolveIdentityUseCase,
    ResolveByIndexUseCase {

    // Tier-2′ Fellegi-Sunter scorer (ADR-0072). Pure domain, default m/u priors + gray-zone/high
    // thresholds; tunable later from production link decisions.
    private val matcher = ProbabilisticMatcher()

    override suspend fun resolve(command: ResolveIdentityCommand): ResolutionResult {
        // ── Tier 0: verified EUDI PID subject identifier (ADR-0094 — strongest key, above RČ) ─
        if (!command.eudiPidSubVerified.isNullOrBlank()) {
            val tier0 = resolveTier0(command.eudiPidSubVerified)
            if (tier0 != null) return tier0 // MatchExisting; null → fall through (link on create)
        }

        // ── Tier 1: Czech RČ blind index ─────────────────────────────────────────
        if (!command.birthNumberRaw.isNullOrBlank()) {
            val tier1 = resolveTier1(command)
            if (tier1 != null) return tier1
        }

        // ── Tier 2: normalized candidate match (no RČ or unresolvable via tier 1) ─
        return resolveTier2(command)
    }

    /**
     * Tier-0 (ADR-0094): deterministic match on the VERIFIED EUDI PID subject identifier. The id is
     * blind-indexed (HMAC-SHA256, same pepper as RČ — a government identifier is never stored raw) and
     * looked up against `party_external_ids(EUDI_PID_SUB)`. Hit → [ResolutionResult.MatchExisting] (the
     * presentation was cryptographically verified at eIDAS-High, so no attribute cross-check is needed).
     * Miss → null: fall through so a person first seen via RČ is still found, then EUDI_PID_SUB is linked
     * at create time. Returns null (skips tier-0) if the pepper is absent.
     */
    private suspend fun resolveTier0(eudiPidSubVerified: String): ResolutionResult? {
        val pepper = pepperHex.orElse("").takeIf { it.isNotBlank() }
        if (pepper == null) {
            Log.warn("IdentityResolutionService: pepper absent — tier-0 EUDI PID lookup skipped")
            return null
        }
        val eudiIndex = BlindIndex.compute(pepper.decodeHex(), eudiPidSubVerified)
        val match = partyRepository.findByExternalId(ExternalIdType.EUDI_PID_SUB, eudiIndex) ?: return null
        return ResolutionResult.MatchExisting(match.id)
    }

    /**
     * Direct lookup by a pre-computed blind index (ADR-0072).
     * Returns the matched party's UUID or null.  Does not perform attribute cross-checks —
     * the caller holds only the index, not the plaintext RČ or core attributes.
     */
    override suspend fun resolveByIndex(blindIndex: String): UUID? =
        partyRepository.findByExternalId(ExternalIdType.BIRTH_NUMBER, blindIndex)?.id

    /**
     * Compute the blind index for a raw RČ using the service pepper.
     * Returns `null` when the pepper is absent (CI / no-Vault local dev) or the RČ is invalid.
     */
    fun computeBlindIndex(birthNumberRaw: String): String? {
        val pepper = pepperHex.orElse("").takeIf { it.isNotBlank() } ?: return null
        val rc = RodneCislo.parse(birthNumberRaw)
        if (rc is RodneCislo.Invalid) return null
        return BlindIndex.compute(pepper.decodeHex(), (rc as RodneCislo.Parsed).canonical)
    }

    // ── internal ─────────────────────────────────────────────────────────────────

    /**
     * Tier-1 resolution via RČ blind index.
     *
     * Returns a [ResolutionResult] when tier-1 is **conclusive** (RČ valid and pepper present):
     * - RČ found in index: [ResolutionResult.MatchExisting] or [ResolutionResult.NeedsManualVerification]
     * - RČ not found in index: [ResolutionResult.NoMatch]
     *
     * Returns `null` when tier-1 is **inconclusive** (pepper absent, or RČ invalid/unparseable):
     * the caller must fall through to tier-2.
     */
    private suspend fun resolveTier1(command: ResolveIdentityCommand): ResolutionResult? {
        val raw = command.birthNumberRaw ?: return null

        val pepper = pepperHex.orElse("").takeIf { it.isNotBlank() }
        if (pepper == null) {
            Log.warn(
                "IdentityResolutionService: birth-number-pepper is not configured — " +
                    "tier-1 RČ blind-index lookup skipped; tier-2 match-key will run instead.",
            )
            return null
        }

        val rcResult = RodneCislo.parse(raw)
        if (rcResult is RodneCislo.Invalid) {
            Log.warn(
                "IdentityResolutionService: RČ parse failed (${rcResult.reason}) — " +
                    "skipping tier-1, falling through to tier-2",
            )
            return null
        }
        val rc = rcResult as RodneCislo.Parsed

        val pepperBytes = pepper.decodeHex()
        val blindIndex = BlindIndex.compute(pepperBytes, rc.canonical)

        val match = partyRepository.findByExternalId(ExternalIdType.BIRTH_NUMBER, blindIndex)
            ?: return ResolutionResult.NoMatch // conclusive: no party with this RČ — tier-2 not needed

        // ── Attribute cross-check (collision adjudication, ADR-0072 §1) ──────────
        // The RČ-derived birthdate and gender must match the stored party. A mismatch
        // could be a data-entry error, identity fraud, or historic RČ reissue. Do NOT
        // auto-merge — escalate to the highest-rigour manual case.
        val birthdateMatch = rc.birthdate == match.coreAttributes.birthdate
        val genderMatch = rcGenderToModel(rc.gender) == match.coreAttributes.gender ||
            match.coreAttributes.gender == null // gender not yet recorded → don't block

        return if (birthdateMatch && genderMatch) {
            ResolutionResult.MatchExisting(match.id)
        } else {
            Log.warn(
                "IdentityResolutionService: RČ blind-index collision with divergent attributes " +
                    "— partyId=${match.id} birthdateMatch=$birthdateMatch genderMatch=$genderMatch — " +
                    "routing to manual verification (RN_COLLISION)",
            )
            adjudicate(
                dedupKey = "RN:$blindIndex",
                command = command,
                candidates = listOf(match),
                trigger = VerificationTrigger.RN_COLLISION,
                blindIndex = blindIndex,
            )
        }
    }

    private suspend fun resolveTier2(command: ResolveIdentityCommand): ResolutionResult {
        val matchKey = MatchKey.of(
            familyName = command.familyName,
            givenName = command.givenName,
            birthdate = command.birthdate,
            birthplace = command.birthplace,
        )
        val exact = partyRepository.findCandidatesByMatchKey(matchKey)
        if (exact.isNotEmpty()) {
            return adjudicate(
                dedupKey = "NK:$matchKey",
                command = command,
                candidates = exact,
                trigger = VerificationTrigger.NAMESAKE_CANDIDATE,
                blindIndex = null,
            )
        }
        return resolveTier2Probabilistic(command, matchKey)
    }

    /**
     * Tier-2′ — probabilistic record linkage (ADR-0072). When no RČ and no exact match-key candidate
     * exists, score the applicant against a loose candidate set (same birth year + family initial)
     * with the Fellegi-Sunter [matcher]. Any gray-zone-or-better score is a likely fuzzy duplicate
     * (typo, diacritics, day-of-birth slip) → four-eyes (NEVER auto-merged). No scoring hit → NoMatch.
     */
    private suspend fun resolveTier2Probabilistic(
        command: ResolveIdentityCommand,
        matchKey: String,
    ): ResolutionResult {
        val familyInitial = command.familyName.trim().take(1)
        if (familyInitial.isBlank()) return ResolutionResult.NoMatch

        val candidates = partyRepository.findCandidatesForProbabilistic(familyInitial, command.birthdate.year)
        if (candidates.isEmpty()) return ResolutionResult.NoMatch

        val applicant = IdentityAttributes(
            givenName = command.givenName,
            familyName = command.familyName,
            birthdate = command.birthdate,
            birthplace = command.birthplace,
        )
        val likely = candidates.filter {
            matcher.score(applicant, it.toIdentityAttributes()).band != MatchBand.NO_MATCH
        }
        if (likely.isEmpty()) return ResolutionResult.NoMatch

        Log.info(
            "IdentityResolutionService: tier-2′ probabilistic match — ${likely.size} candidate(s) in gray zone " +
                "or above for a no-RČ applicant; routing to manual verification (PROBABILISTIC_CANDIDATE)",
        )
        return adjudicate(
            dedupKey = "PK:$matchKey",
            command = command,
            candidates = likely,
            trigger = VerificationTrigger.PROBABILISTIC_CANDIDATE,
            blindIndex = null,
        )
    }

    /**
     * Turn an ambiguous resolution into a four-eyes outcome (ADR-0072 §1).
     *
     * First consults the adjudication cache: a prior DECIDED case for this dedup key deterministically
     * steers the result — LINK_TO_EXISTING → [ResolutionResult.MatchExisting], DISTINCT_NEW →
     * [ResolutionResult.NoMatch], REJECT → stays blocked. Otherwise opens (or reuses) a durable case
     * and returns [ResolutionResult.NeedsManualVerification] carrying the real caseId.
     */
    private suspend fun adjudicate(
        dedupKey: String,
        command: ResolveIdentityCommand,
        candidates: List<Party>,
        trigger: VerificationTrigger,
        blindIndex: String?,
    ): ResolutionResult {
        adjudication.priorDecision(dedupKey)?.let { prior ->
            return when (prior.verdict) {
                CaseVerdict.LINK_TO_EXISTING -> ResolutionResult.MatchExisting(prior.linkPartyId!!)
                CaseVerdict.DISTINCT_NEW -> ResolutionResult.NoMatch
                CaseVerdict.REJECT -> ResolutionResult.NeedsManualVerification(
                    caseId = prior.caseId,
                    candidates = candidates.map { it.toCandidate() },
                    trigger = trigger,
                )
            }
        }
        val caseId = adjudication.openOrReuse(
            OpenCaseCommand(
                dedupKey = dedupKey,
                trigger = trigger,
                applicant = ApplicantSnapshot(
                    givenName = command.givenName,
                    familyName = command.familyName,
                    birthdate = command.birthdate,
                    birthplace = command.birthplace,
                    nationalities = command.nationalities,
                ),
                blindIndex = blindIndex,
                candidatePartyIds = candidates.map { it.id },
            ),
        )
        return ResolutionResult.NeedsManualVerification(
            caseId = caseId,
            candidates = candidates.map { it.toCandidate() },
            trigger = trigger,
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun Party.toCandidate() = CandidateSummary(
        partyId = id,
        nameMasked = buildString {
            append(coreAttributes.familyName.first().uppercaseChar())
            append(". ")
            append(coreAttributes.givenName.first().uppercaseChar())
            append(".")
        },
        birthYear = coreAttributes.birthdate.year,
    )

    private fun Party.toIdentityAttributes() = IdentityAttributes(
        givenName = coreAttributes.givenName,
        familyName = coreAttributes.familyName,
        birthdate = coreAttributes.birthdate,
        birthplace = coreAttributes.birthplace,
    )

    private fun rcGenderToModel(rcGender: RodneCislo.Gender): Gender? = when (rcGender) {
        RodneCislo.Gender.MALE -> Gender.MALE
        RodneCislo.Gender.FEMALE -> Gender.FEMALE
    }

    /** Decode a lowercase hex string to a byte array. */
    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
