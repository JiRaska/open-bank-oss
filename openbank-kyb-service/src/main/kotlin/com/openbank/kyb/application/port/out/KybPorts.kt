// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.util.UUID

/** The public register could not be reached or answered outside its contract — distinct from "not found". */
class RegistryUnavailableException(source: String, cause: Throwable? = null) :
    RuntimeException("register $source is unavailable", cause)

/**
 * One public register (ADR-0284 D1). [supports] is the routing key; [lookup] returns null for an
 * entity the register does not know and throws [RegistryUnavailableException] on an outage, so
 * the two cannot be confused by a caller.
 */
interface RegistryAdapter {
    val source: String
    fun supports(scheme: IdentifierScheme): Boolean
    suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract?
}

/** The router over every [RegistryAdapter]; what the use cases depend on. */
interface BusinessRegistryPort {
    suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract?
}

/** Short-lived cache of extracts so a lookup on the entry screen and the case start share one register call. */
interface RegistryExtractCache {
    suspend fun find(identifier: LegalEntityIdentifier, notOlderThan: Instant): RegistryExtract?
    suspend fun put(extract: RegistryExtract)
}

interface BusinessOnboardingCaseRepository {
    suspend fun save(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase
    suspend fun update(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase
    suspend fun findById(id: UUID): BusinessOnboardingCase?
    suspend fun findOpenByIdentifier(identifier: LegalEntityIdentifier): BusinessOnboardingCase?
    suspend fun findByInvitationToken(token: String): BusinessOnboardingCase?
    suspend fun findByEntityPartyId(entityPartyId: UUID): BusinessOnboardingCase?

    /** Cases the party initiated OR is a signer on. */
    suspend fun findInvolving(partyId: UUID): List<BusinessOnboardingCase>
    suspend fun listByStatus(status: CaseStatus, page: Int, size: Int): List<BusinessOnboardingCase>
}

interface KybOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}

/** What party-service must do for a business case: mint the entity party, then bind the humans to it. */
data class EntityPartyRequest(
    val partyType: String,
    val legalName: String,
    val registrationNumber: String,
    val registrationCountry: String?,
    val legalForm: String?,
    val taxId: String?,
    val addressLine1: String?,
    val city: String?,
    val postalCode: String?,
    val countryCode: String?,
    /** Idempotency key — the case id, so a retried start never mints a second party. */
    val idempotencyKey: String,
)

data class MandateRequest(
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val role: String,
    val authority: String,
    val source: String,
    val evidenceRef: String,
)

interface PartyGateway {
    suspend fun createEntityParty(request: EntityPartyRequest): UUID
    suspend fun grantMandate(request: MandateRequest)
}

/** Opaque, unguessable invitation tokens. A port so tests can make them deterministic. */
interface InvitationTokens {
    fun next(): String
}

/** Domain counters for the business funnel; a port so the use case stays framework-free. */
interface KybMetricsPort {
    /** A case was opened: [scheme] is the identifier scheme, [outcome] the status it landed in (REGISTRY_VERIFIED | MANUAL_REVIEW). */
    fun caseStarted(scheme: String, outcome: String)

    /** A public register answered: [outcome] is `found` | `not_found` | `unavailable`. */
    fun registryLookup(source: String, outcome: String)

    /** Temporal could not be told about a state change; the case is correct, its timers are not armed. */
    fun timerArmingFailed(state: String)
}

/**
 * Durable timers over the case (ADR-0284): told about every state change, arms reminders,
 * invitation expiry and abandonment. A failure here must never fail the customer's request —
 * the use case logs and counts it; the case is correct without a timer, only slower to expire.
 */
interface BusinessOnboardingWorkflowPort {
    fun stateEntered(caseId: UUID, state: CaseStatus)
}

/**
 * One jurisdiction's beneficial-ownership register (ADR-0284 D5).
 *
 * Separate from [RegistryAdapter] rather than another method on it, because the two registers are
 * genuinely different sources: the UK publishes companies in Companies House and their PSCs through
 * the same API, while the Czech `Evidence skutečných majitelů` is a different register with no
 * public API at all. Folding them together would make "the register answered" ambiguous about which
 * register.
 */
interface UboAdapter {
    val source: String

    fun supports(scheme: IdentifierScheme): Boolean

    /**
     * The finding, or null when this adapter does not answer for the identifier at all. An adapter
     * that reaches its register and learns nothing returns a finding with no owners — the caller
     * cannot tell "no owners" from "did not look" if both are null.
     */
    suspend fun lookup(identifier: LegalEntityIdentifier, pack: CountryPack): UboFinding?
}

/** The router over every [UboAdapter]; what the use cases depend on. */
interface BeneficialOwnershipPort {
    suspend fun lookup(identifier: LegalEntityIdentifier): UboFinding
}
