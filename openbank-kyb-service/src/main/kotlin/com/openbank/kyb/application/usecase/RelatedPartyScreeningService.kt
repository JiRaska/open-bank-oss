// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningRequest
import com.openbank.kyb.application.port.out.RelatedPartyScreeningUnavailableException
import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
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
 * beneficial owner) against the sanctions, terrorist and PEP lists, and records the answer on the
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
     * The related parties are known (the declarations name the beneficial owners): rebuild the
     * subject list from the register's current answer, keep what was already screened, screen the rest.
     */
    suspend fun screenRelatedParties(case: BusinessOnboardingCase, owners: List<BeneficialOwner>): BusinessOnboardingCase {
        if (!required) return case
        val extract = case.extract ?: return case
        val fresh = CaseScreening.subjectsOf(extract, owners)
        return screenPending(case, case.screening?.mergedInto(fresh) ?: CaseScreening(fresh))
    }

    /**
     * Called before an approval is attempted. Screens whatever still has no answer; a case that was
     * never screened (it made its declarations before this control existed, or while sanctions-service
     * was down) is screened from scratch. Returns the SAME instance when there was nothing to do.
     */
    suspend fun ensureScreened(case: BusinessOnboardingCase): BusinessOnboardingCase {
        if (!required || case.screening?.complete == true) return case
        val extract = case.extract ?: return case
        val current = case.screening
            ?: CaseScreening(CaseScreening.subjectsOf(extract, ubo.lookup(case.identifier).reportableOwners))
        return screenPending(case, current)
    }

    private suspend fun screenPending(case: BusinessOnboardingCase, screening: CaseScreening): BusinessOnboardingCase {
        val now = Instant.now(clock)
        val subjects = screening.subjects.map { subject ->
            if (subject.sanctions != ScreeningVerdict.PENDING) subject else screenOne(case, subject, now)
        }
        return case.screeningRecorded(CaseScreening(subjects), now)
    }

    private suspend fun screenOne(case: BusinessOnboardingCase, subject: ScreeningSubject, now: Instant): ScreeningSubject =
        try {
            val result = port.screen(
                RelatedPartyScreeningRequest(
                    name = subject.name,
                    subjectId = subjectId(case, subject),
                    organisation = subject.organisation,
                    idempotencyKey = "kyb-screen:${case.id}:${subject.key}",
                ),
            )
            metrics.relatedPartyScreened(outcome(result.sanctions, result.pep))
            subject.copy(
                sanctions = result.sanctions,
                pep = result.pep,
                matchedName = result.matchedName,
                screenedAt = now,
            )
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

    private fun outcome(sanctions: ScreeningVerdict, pep: Boolean): String = when {
        sanctions == ScreeningVerdict.HIT -> "hit"
        sanctions == ScreeningVerdict.POTENTIAL_HIT -> "potential_hit"
        pep -> "pep"
        else -> "clear"
    }
}
