// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningRequest
import com.openbank.kyb.application.port.out.RelatedPartyScreeningResult
import com.openbank.kyb.application.port.out.RelatedPartyScreeningUnavailableException
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.SubjectIdentity
import com.openbank.kyb.domain.model.UboFinding
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Screens the entity and its related parties (every listed representative, every reportable
 * beneficial owner, every person named in the declarations) against the sanctions, terrorist and PEP lists, and records the answer on the
 * case. It never decides anything: the aggregate gates approval on what is recorded here.
 *
 * Three outcomes per subject, kept apart on purpose:
 *  - an answer (clear, potential hit, hit, with the PEP fact beside it) is recorded;
 *  - no answer (sanctions-service unreachable) leaves the subject PENDING — never clear — and the
 *    next call tries that subject again under the same idempotency key;
 *  - the control switched off records nothing at all, so an unscreened case can never read as a
 *    screened one.
 *
 * `openbank.kyb.screening.enabled` is OFF by default: the deployment has to supply the
 * sanctions-service URL and network path together with the switch, or every business case would
 * stop at its last signature. `@Startup` so the "switched off" warning is emitted at boot rather
 * than on the first case that reaches a signature.
 */
@Startup
@ApplicationScoped
class RelatedPartyScreeningService {

    @Inject lateinit var port: RelatedPartyScreeningPort

    @Inject lateinit var ubo: BeneficialOwnershipPort

    @Inject lateinit var metrics: KybMetricsPort

    @Inject lateinit var clock: Clock

    @ConfigProperty(name = "openbank.kyb.screening.enabled", defaultValue = "false")
    lateinit var enabled: Optional<Boolean>

    private val log = Logger.getLogger(RelatedPartyScreeningService::class.java)

    /** Whether approval must wait for a complete screening answer. */
    val required: Boolean get() = enabled.orElse(false)

    @PostConstruct
    fun announce() {
        if (!required) {
            log.warn(
                "related-party sanctions/PEP screening is SWITCHED OFF (openbank.kyb.screening.enabled=false): " +
                    "business onboarding cases are approved WITHOUT a sanctions screen of the entity, its " +
                    "representatives and its beneficial owners",
            )
        }
    }

    /**
     * Brings the screening up to date with who is on the case NOW, and screens whoever has no
     * answer. Called when the declarations are made and again before every approval.
     *
     * The subject list is REBUILT on every call — the entity, the register's representatives, the
     * beneficial owners the ownership register reports at this moment, and every person the
     * customer named in the declarations — and answers are carried over by subject. A screening
     * that was complete for yesterday's owners is therefore not complete once a new owner
     * appears: that person arrives PENDING and approval waits for them. When the ownership lookup
     * itself is unavailable the list cannot be trusted to be whole, which [CaseScreening.complete]
     * refuses on its own.
     *
     * Returns the SAME instance when nothing changed. An unreachable sanctions-service never
     * fails this call; it leaves subjects PENDING, and the aggregate refuses the approval.
     */
    suspend fun screened(case: BusinessOnboardingCase, known: UboFinding? = null): BusinessOnboardingCase {
        if (!required) return case
        val extract = case.extract ?: return case
        val finding = known ?: ubo.lookup(case.identifier)
        val fresh = CaseScreening.subjectsOf(
            extract,
            finding.reportableOwners,
            case.declarations?.peps.orEmpty().map { it.name },
        )
        val identities = CaseScreening.identitiesOf(extract, finding.reportableOwners)
        val now = Instant.now(clock)
        val current = (case.screening ?: CaseScreening(emptyList())).rebuilt(fresh, finding.source)
        val next = current.copy(
            subjects = current.subjects.map { subject ->
                if (!subject.current || subject.sanctions != ScreeningVerdict.PENDING) {
                    subject
                } else {
                    val ids = identities[subject.key].orEmpty().ifEmpty { listOf(SubjectIdentity()) }
                    screenOne(case, subject, ids, now)
                }
            },
        )
        return if (next == case.screening) case else case.screeningRecorded(next, now)
    }

    private suspend fun screenOne(
        case: BusinessOnboardingCase,
        subject: ScreeningSubject,
        identities: List<SubjectIdentity>,
        now: Instant,
    ): ScreeningSubject = try {
        // One screen per distinct identity under this name; the subject takes the WORST answer,
        // so a second person sharing the name can never be cleared by the first one's facts.
        // Each key is derived from WHAT is screened, not from its position: sanctions-service
        // replays a stored verdict by key, so a key that outlived a change in the register facts
        // would answer for an identity it never screened.
        val result = identities.map { identity ->
            port.screen(
                RelatedPartyScreeningRequest(
                    name = subject.name,
                    subjectId = subjectId(case, subject),
                    organisation = subject.organisation,
                    idempotencyKey = screenKey(case.id, subject, identity),
                    identity = identity,
                ),
            )
        }.let(::worst)
        metrics.relatedPartyScreened(outcome(result.sanctions, result.pep))
        subject.answered(result.sanctions, result.pep, result.matchedName, now)
    } catch (e: RelatedPartyScreeningUnavailableException) {
        // Deliberately not rethrown: the customer's step (declarations) must not fail on an
        // outage. The subject stays PENDING, which is what refuses the approval later.
        log.warnf(e, "related-party screening unavailable for kyb case %s (%s)", case.id, subject.kind)
        metrics.relatedPartyScreened("unavailable")
        subject
    }

    /** The entity's party id where it has one, so an analyst's clearance follows the party; else a case-scoped id. */
    private fun subjectId(case: BusinessOnboardingCase, subject: ScreeningSubject): String =
        case.entityPartyId?.takeIf { subject.kind == ScreeningSubjectKind.ENTITY }?.toString()
            ?: "kyb-case:${case.id}:${subject.key}"

    private fun worst(results: List<RelatedPartyScreeningResult>): RelatedPartyScreeningResult {
        val deciding = results.maxBy { it.sanctions.ordinal }
        return deciding.copy(
            pep = results.any { it.pep },
            matchedName = deciding.matchedName ?: results.firstNotNullOfOrNull { it.matchedName },
        )
    }

    private fun outcome(sanctions: ScreeningVerdict, pep: Boolean): String = when {
        sanctions == ScreeningVerdict.HIT -> "hit"
        sanctions == ScreeningVerdict.POTENTIAL_HIT -> "potential_hit"
        pep -> "pep"
        else -> "clear"
    }

    internal companion object {
        private const val KEY_HEX_CHARS = 32

        /** Idempotency key of one screen: the case, the subject and a digest of everything sent. */
        fun screenKey(caseId: UUID, subject: ScreeningSubject, identity: SubjectIdentity): String {
            val content = listOf(
                subject.name,
                subject.organisation.toString(),
                identity.country?.trim()?.uppercase().orEmpty(),
                identity.dateOfBirth?.toString().orEmpty(),
                identity.nationality?.trim()?.uppercase().orEmpty(),
                identity.aliases.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString("\u0001"),
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return "kyb-screen:$caseId:${subject.key}:" +
                digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX_CHARS)
        }
    }
}
