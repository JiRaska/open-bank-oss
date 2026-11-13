// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningRequest
import com.openbank.kyb.application.port.out.RelatedPartyScreeningUnavailableException
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.UboFinding
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.Optional

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
        val now = Instant.now(clock)
        // Every subject gets its opaque reference BEFORE it is screened: the screen sends it as
        // `subjectId`, and ongoing monitoring later registers the subject under the same value,
        // which is what lets an analyst's clearance of the subject be honoured there (ADR-0376 D1).
        val current = (case.screening ?: CaseScreening(emptyList())).rebuilt(fresh, finding.source)
            .withRefs(case.entityPartyId?.toString(), MonitoringRegistrationService::newSubjectRef)
        val next = current.copy(
            subjects = current.subjects.map { subject ->
                if (!subject.current || subject.sanctions != ScreeningVerdict.PENDING) {
                    subject
                } else {
                    screenOne(case, subject, now)
                }
            },
        )
        return if (next == case.screening) case else case.screeningRecorded(next, now)
    }

    private suspend fun screenOne(
        case: BusinessOnboardingCase,
        subject: ScreeningSubject,
        now: Instant,
    ): ScreeningSubject = try {
        val result = port.screen(
            RelatedPartyScreeningRequest(
                name = subject.name,
                subjectId = subjectId(subject),
                organisation = subject.organisation,
                idempotencyKey = "kyb-screen:${case.id}:${subject.key}",
            ),
        )
        metrics.relatedPartyScreened(outcome(result.sanctions, result.pep))
        subject.answered(result.sanctions, result.pep, result.matchedName, now)
    } catch (e: RelatedPartyScreeningUnavailableException) {
        // Deliberately not rethrown: the customer's step (declarations) must not fail on an
        // outage. The subject stays PENDING, which is what refuses the approval later.
        log.warnf(e, "related-party screening unavailable for kyb case %s (%s)", case.id, subject.kind)
        metrics.relatedPartyScreened("unavailable")
        subject
    }

    /**
     * The subject's opaque reference: the entity's party id where it had one when the reference
     * was given, a random UUID otherwise. It used to be `kyb-case:<case>:<key>`, whose key is a
     * hash of the person's name — a pseudonym, not an opaque id, and unfit for a URL path.
     */
    private fun subjectId(subject: ScreeningSubject): String =
        requireNotNull(subject.ref) { "a subject is given its reference before it is screened" }

    private fun outcome(sanctions: ScreeningVerdict, pep: Boolean): String = when {
        sanctions == ScreeningVerdict.HIT -> "hit"
        sanctions == ScreeningVerdict.POTENTIAL_HIT -> "potential_hit"
        pep -> "pep"
        else -> "clear"
    }
}
