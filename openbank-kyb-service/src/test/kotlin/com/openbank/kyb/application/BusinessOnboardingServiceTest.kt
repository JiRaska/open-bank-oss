// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.`in`.ClaimInvitationCommand
import com.openbank.kyb.application.port.`in`.InviteCosignersCommand
import com.openbank.kyb.application.port.`in`.MatchInitiatorCommand
import com.openbank.kyb.application.port.`in`.ResolveReviewCommand
import com.openbank.kyb.application.port.`in`.SignCommand
import com.openbank.kyb.application.port.`in`.StartCaseCommand
import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.BusinessOnboardingWorkflowPort
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.InvitationTokens
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.application.usecase.BusinessOnboardingService
import com.openbank.kyb.application.usecase.CaseCallerMismatchException
import com.openbank.kyb.application.usecase.RegistryLookupService
import com.openbank.kyb.application.usecase.RepresentationAttestationService
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class BusinessOnboardingServiceTest {

    private val now = Instant.parse("2026-09-05T10:00:00Z")
    private val initiator = UUID.randomUUID()
    private val entityParty = UUID.randomUUID()
    private val ico = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649")

    private val registry = mockk<BusinessRegistryPort>()
    private val cache = mockk<RegistryExtractCache>(relaxed = true)
    private val parties = mockk<PartyGateway>()
    private val metrics = mockk<KybMetricsPort>(relaxed = true)
    private val timers = mockk<BusinessOnboardingWorkflowPort>(relaxed = true)

    /** In-memory repository: the service's transitions are what is under test, not JPQL. */
    private val store = linkedMapOf<UUID, BusinessOnboardingCase>()
    private val events = mutableListOf<KybEvent>()
    private val repo = object : BusinessOnboardingCaseRepository {
        override suspend fun save(case: BusinessOnboardingCase, event: KybEvent?) = case.also {
            store[it.id] = it
            event?.let(events::add)
        }
        override suspend fun update(case: BusinessOnboardingCase, event: KybEvent?) = case.also {
            store[it.id] = it
            event?.let(events::add)
        }
        override suspend fun findById(id: UUID) = store[id]
        override suspend fun findOpenByIdentifier(identifier: LegalEntityIdentifier) =
            store.values.firstOrNull { it.identifier == identifier && !it.status.isTerminal }
        override suspend fun findByInvitationToken(token: String) = store.values.firstOrNull { c ->
            c.signers.any {
                it.invitationToken ==
                    token
            }
        }
        override suspend fun findByEntityPartyId(entityPartyId: UUID) = store.values.firstOrNull {
            it.entityPartyId ==
                entityPartyId
        }
        override suspend fun findInvolving(partyId: UUID) = store.values.filter { c ->
            c.initiatorPartyId == partyId ||
                c.signers.any { it.partyId == partyId }
        }
        override suspend fun listByStatus(status: CaseStatus, page: Int, size: Int) = store.values.filter {
            it.status ==
                status
        }
    }

    private lateinit var service: BusinessOnboardingService

    @BeforeEach
    fun setUp() {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val lookup = RegistryLookupService().apply {
            this.registry = this@BusinessOnboardingServiceTest.registry
            this.cache = this@BusinessOnboardingServiceTest.cache
            this.clock = clock
            cacheTtl = Duration.ofHours(24)
        }
        coEvery { cache.find(any(), any()) } returns null
        // Every rule these tests use has been confirmed by an operator (#9711); they are about the
        // onboarding flow that FOLLOWS confirmation. `PreAttested` answers the human's confirmation
        // for whatever rule text is asked about — the confirmation itself is exercised in
        // RepresentationAttestationServiceTest and the flow's dependence on it in
        // BusinessOnboardingCaseTest.
        val representation = AlwaysConfirmed().apply {
            this.attestations = NoStore()
            this.lookup = lookup
            this.clock = clock
        }
        var n = 0
        service = BusinessOnboardingService().apply {
            cases = repo
            this.lookup = lookup
            this.parties = this@BusinessOnboardingServiceTest.parties
            tokens = object : InvitationTokens {
                override fun next() = "tok-${++n}"
            }
            this.metrics = this@BusinessOnboardingServiceTest.metrics
            this.timers = this@BusinessOnboardingServiceTest.timers
            this.clock = clock
            this.representation = representation
        }
    }

    /**
     * Treats every rule as already confirmed by an operator, on the register's own numbers. These
     * tests are about the onboarding flow that FOLLOWS confirmation; the confirmation itself is
     * exercised by RepresentationAttestationServiceTest, and the flow's dependence on it — that an
     * unconfirmed rule cannot proceed — by BusinessOnboardingCaseTest.
     */
    private class AlwaysConfirmed : RepresentationAttestationService() {
        override suspend fun decide(extract: RegistryExtract) = RepresentationDecision.Attested(
            RepresentationAttestation(
                id = UUID.randomUUID(),
                identifier = extract.identifier,
                ruleTextHash = RepresentationAttestation.hashOf(extract.representationRule.sourceText),
                ruleText = extract.representationRule.sourceText,
                parsedMode = extract.representationRule.mode,
                parsedSigners = extract.representationRule.requiredSigners,
                confirmedSigners = extract.representationRule.requiredSigners
                    ?: extract.representatives.size.coerceAtLeast(1),
                confirmedRoles = extract.representationRule.requiredRoles,
                attestedBy = "operator-test",
                attestedAt = Instant.parse("2026-09-11T10:00:00Z"),
            ),
        )
    }

    private class NoStore : RepresentationAttestationRepository {
        override suspend fun findActive(identifier: LegalEntityIdentifier, ruleTextHash: String) = null

        override suspend fun findLatestFor(identifier: LegalEntityIdentifier) = null

        override suspend fun attest(attestation: RepresentationAttestation) = attestation

        override suspend fun listFor(identifier: LegalEntityIdentifier) = emptyList<RepresentationAttestation>()
    }

    private fun extract(
        rule: RepresentationRule,
        reps: List<String>,
        form: LegalFormClass = LegalFormClass.LIMITED_COMPANY,
        /** Register role per representative, positionally; defaults to `jednatel` for all. */
        roles: List<String> = emptyList(),
    ) = RegistryExtract(
        identifier = ico,
        legalName = "Příklad s.r.o.",
        legalFormCode = "112",
        legalFormClass = form,
        status = EntityStatus.ACTIVE,
        registeredAddress = null,
        incorporatedOn = null,
        taxId = "CZ45274649",
        representatives = reps.mapIndexed { i, name ->
            Representative(name, LocalDate.of(1980, 1, 1), "jednatelé", roles.getOrNull(i) ?: "jednatel", null)
        },
        representationRule = rule,
        source = "ares",
        sourceRef = null,
        verification = ExtractVerification.VERIFIED,
        fetchedAt = now,
    )

    @Test
    fun `start verifies the register, mints the entity party and emits BUSINESS_REGISTRY_VERIFIED`(): Unit =
        runBlocking {
            coEvery { registry.lookup(ico, null) } returns extract(RepresentationRule.SOLE, listOf("Jana Nováková"))
            val req = slot<EntityPartyRequest>()
            coEvery { parties.createEntityParty(capture(req)) } returns entityParty

            val case = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "452 746 49", initiator))

            assertThat(case.status).isEqualTo(CaseStatus.REGISTRY_VERIFIED)
            assertThat(case.entityPartyId).isEqualTo(entityParty)
            assertThat(req.captured.partyType).isEqualTo("COMPANY")
            assertThat(req.captured.registrationNumber).isEqualTo("45274649")
            assertThat(req.captured.idempotencyKey).isEqualTo(case.id.toString())
            assertThat(events.map { it.eventType }).containsExactly(KybEvents.REGISTRY_VERIFIED)
            assertThat(events.single().payload.sourceService).isEqualTo("kyb-service")
            coVerify(exactly = 1) { cache.put(any()) }
            io.mockk.verify(exactly = 1) { timers.stateEntered(case.id, CaseStatus.REGISTRY_VERIFIED) }
        }

    @Test
    fun `a Temporal failure never fails the customer step - it is counted and the case stands`(): Unit = runBlocking {
        coEvery { registry.lookup(ico, null) } returns extract(RepresentationRule.SOLE, listOf("Jana Nováková"))
        coEvery { parties.createEntityParty(any()) } returns entityParty
        io.mockk.every { timers.stateEntered(any(), any()) } throws IllegalStateException("temporal down")

        val case = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))

        assertThat(case.status).isEqualTo(CaseStatus.REGISTRY_VERIFIED)
        assertThat(store[case.id]).isNotNull
        io.mockk.verify(exactly = 1) { metrics.timerArmingFailed("REGISTRY_VERIFIED") }
    }

    @Test
    fun `the timer abandons only a case still in the expected state`(): Unit = runBlocking {
        coEvery { registry.lookup(ico, null) } returns extract(RepresentationRule.SOLE, listOf("Jana Nováková"))
        coEvery { parties.createEntityParty(any()) } returns entityParty
        val case = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))

        assertThat(service.abandonIfInState(case.id, "AWAITING_COSIGNERS", "temporal-timer")).isFalse()
        assertThat(store[case.id]!!.status).isEqualTo(CaseStatus.REGISTRY_VERIFIED)
        assertThat(service.abandonIfInState(case.id, "REGISTRY_VERIFIED", "temporal-timer")).isTrue()
        assertThat(store[case.id]!!.status).isEqualTo(CaseStatus.ABANDONED)
        assertThat(events.last().eventType).isEqualTo(KybEvents.ABANDONED)
        assertThat(events.last().payload.actorId).isEqualTo("temporal-timer")
    }

    @Test
    fun `an unknown identifier opens the case in MANUAL_REVIEW without touching party-service`(): Unit = runBlocking {
        coEvery { registry.lookup(ico, null) } returns null
        val case = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
        assertThat(case.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(case.entityPartyId).isNull()
        coVerify(exactly = 0) { parties.createEntityParty(any()) }
        assertThat(events.map { it.eventType }).containsExactly(KybEvents.REVIEW_REQUIRED)
    }

    @Test
    fun `a second person cannot open a competing case for an entity already being onboarded`(): Unit = runBlocking {
        coEvery { registry.lookup(ico, null) } returns extract(RepresentationRule.SOLE, listOf("Jana Nováková"))
        coEvery { parties.createEntityParty(any()) } returns entityParty
        val first = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
        assertThat(
            service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator)).id,
        ).isEqualTo(first.id)
        assertThatThrownBy {
            runBlocking { service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", UUID.randomUUID())) }
        }
            .isInstanceOf(CaseCallerMismatchException::class.java)
    }

    @Test
    fun `full two-signer flow grants one mandate per signature and completes on party activation`(): Unit =
        runBlocking {
            coEvery { registry.lookup(ico, null) } returns
                extract(
                    RepresentationRule(RepresentationMode.JOINT_N, 2, "dva společně"),
                    listOf("Jana Nováková", "Eva Dvořáková"),
                )
            coEvery { parties.createEntityParty(any()) } returns entityParty
            val mandates = mutableListOf<MandateRequest>()
            coEvery { parties.grantMandate(capture(mandates)) } returns Unit

            val started = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
            service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Nováková", null))
            val invited = service.inviteCosigners(InviteCosignersCommand(started.id, initiator, listOf(1)))
            val token = invited.signers.first { !it.isInitiator }.invitationToken!!
            assertThat(events.map { it.eventType }).contains(KybEvents.SIGNER_INVITED)

            val cosigner = UUID.randomUUID()
            val claimed = service.claimInvitation(ClaimInvitationCommand(token, cosigner))
            assertThat(claimed.status).isEqualTo(CaseStatus.READY_TO_SIGN)

            service.sign(SignCommand(started.id, initiator, "cer-1"))
            assertThat(mandates).isEmpty() // one signature of two: nothing is granted yet
            val signed = service.sign(SignCommand(started.id, cosigner, "cer-2"))
            assertThat(signed.status).isEqualTo(CaseStatus.SIGNED)
            assertThat(mandates).hasSize(2)
            assertThat(mandates.map { it.agentPartyId }).containsExactlyInAnyOrder(initiator, cosigner)
            assertThat(mandates).allMatch {
                it.principalPartyId == entityParty &&
                    it.role == "LEGAL_REPRESENTATIVE" &&
                    it.authority == "JOINT" &&
                    it.requiredSignatures == 2 &&
                    it.source == "REGISTRY"
            }

            service.entityPartyActivated(entityParty)
            assertThat(store[started.id]!!.status).isEqualTo(CaseStatus.ACTIVE)
            assertThat(events.map { it.eventType }).contains(KybEvents.AGREEMENT_SIGNED, KybEvents.COMPLETED)
        }

    @Test
    fun `a sole trader gets an OWNER mandate and only the initiator may drive their case`(): Unit = runBlocking {
        coEvery { registry.lookup(ico, null) } returns
            extract(RepresentationRule.SOLE, listOf("Jan Novák"), LegalFormClass.SOLE_TRADER)
        coEvery { parties.createEntityParty(any()) } returns entityParty
        val mandate = slot<MandateRequest>()
        coEvery { parties.grantMandate(capture(mandate)) } returns Unit

        val started = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
        assertThatThrownBy {
            runBlocking {
                service.matchInitiator(MatchInitiatorCommand(started.id, UUID.randomUUID(), 0, "Jan Novák", null))
            }
        }
            .isInstanceOf(CaseCallerMismatchException::class.java)
        val ready = service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jan Novák", null))
        assertThat(ready.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        service.sign(SignCommand(started.id, initiator, "cer-1"))
        assertThat(mandate.captured.role).isEqualTo("OWNER")
        assertThat(mandate.captured.authority).isEqualTo("SOLE")
        assertThat(mandate.captured.requiredSignatures).isEqualTo(1)
    }

    @Test
    fun `a review that COMPLETES an already-signed case still grants the mandates`(): Unit = runBlocking {
        // reviewResolved can now finish a case whose signatures were already collected (#9711).
        // grantMandates lives inside sign(), so that path could have produced an ACTIVE entity
        // with NOBODY authorised to act for it — silent from every angle: the case reads
        // complete, and every later request by its own representatives is refused.
        coEvery { registry.lookup(ico, null) } returns
            extract(
                RepresentationRule(
                    RepresentationMode.JOINT_N,
                    2,
                    "předseda spolu s místopředsedou",
                    requiredRoles = listOf("predseda", "mistopredseda"),
                ),
                listOf("Jana Chairová", "Viktor Vice", "Milan Member"),
                roles = listOf("předseda představenstva", "místopředseda představenstva", "člen představenstva"),
            )
        coEvery { parties.createEntityParty(any()) } returns entityParty
        val mandates = mutableListOf<MandateRequest>()
        coEvery { parties.grantMandate(capture(mandates)) } returns Unit

        val started = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
        service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Chairová", null))
        val invited = service.inviteCosigners(InviteCosignersCommand(started.id, initiator, listOf(1, 2)))
        // Everyone identifies, so the offices ARE covered and signing opens.
        invited.signers.filter { !it.isInitiator }.forEach {
            service.claimInvitation(ClaimInvitationCommand(it.invitationToken!!, UUID.randomUUID()))
        }
        val member = service.get(started.id).signers.first { it.fullName == "Milan Member" }.partyId!!

        // The vice never signs: chair + ordinary member reach the COUNT and miss an office.
        service.sign(SignCommand(started.id, initiator, "cer-1"))
        val shortfall = service.sign(SignCommand(started.id, member, "cer-2"))
        assertThat(shortfall.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(mandates).describedAs("nothing is granted while the offices are short").isEmpty()

        // The operator accepts the two collected signatures, dropping the office constraint.
        val accepted = service.resolveReview(ResolveReviewCommand(started.id, 2, "operator-anna"))

        assertThat(accepted.status).isIn(CaseStatus.SIGNED, CaseStatus.ACTIVE)
        assertThat(mandates)
            .describedAs("an entity that reaches SIGNED must carry a mandate per signature")
            .hasSize(2)
    }
}
