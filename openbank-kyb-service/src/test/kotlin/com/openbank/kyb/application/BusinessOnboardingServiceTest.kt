// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.`in`.AcceptDisclosuresCommand
import com.openbank.kyb.application.port.`in`.AnswerQuestionnaireCommand
import com.openbank.kyb.application.port.`in`.ClaimInvitationCommand
import com.openbank.kyb.application.port.`in`.InviteCosignersCommand
import com.openbank.kyb.application.port.`in`.MakeDeclarationsCommand
import com.openbank.kyb.application.port.`in`.MatchInitiatorCommand
import com.openbank.kyb.application.port.`in`.PrepareAgreementCommand
import com.openbank.kyb.application.port.`in`.ResolveReviewCommand
import com.openbank.kyb.application.port.`in`.SignCommand
import com.openbank.kyb.application.port.`in`.StartCaseCommand
import com.openbank.kyb.application.port.out.AgreementDisclosure
import com.openbank.kyb.application.port.out.AgreementProduct
import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.BusinessAgreementRequest
import com.openbank.kyb.application.port.out.BusinessAgreementView
import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.BusinessOnboardingSettings
import com.openbank.kyb.application.port.out.BusinessOnboardingWorkflowPort
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.application.port.out.CeremonySigner
import com.openbank.kyb.application.port.out.CeremonySignerStatus
import com.openbank.kyb.application.port.out.CeremonyStatus
import com.openbank.kyb.application.port.out.DocumentGateway
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.InvitationTokens
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.application.port.out.PepProfile
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.application.usecase.BusinessOnboardingService
import com.openbank.kyb.application.usecase.CaseCallerMismatchException
import com.openbank.kyb.application.usecase.RegistryLookupService
import com.openbank.kyb.application.usecase.RepresentationAttestationService
import com.openbank.kyb.domain.model.AcceptedDisclosure
import com.openbank.kyb.domain.model.AgreementConflictException
import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CrsStatus
import com.openbank.kyb.domain.model.Declarations
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExpectedTurnover
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.FatcaStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.InitiatorIdentity
import com.openbank.kyb.domain.model.InitiatorIdentityMismatchException
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.PepEntry
import com.openbank.kyb.domain.model.Questionnaire
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RelationshipPurpose
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.kyb.domain.model.SourceOfFunds
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboObservation
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.registry.DemoEntity
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
import java.util.Optional
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
    private val observations = mutableListOf<UboObservation>()
    private val repo = object : BusinessOnboardingCaseRepository {
        override suspend fun save(case: BusinessOnboardingCase, event: KybEvent?) = case.also {
            store[it.id] = it
            event?.let(events::add)
        }
        override suspend fun update(case: BusinessOnboardingCase, event: KybEvent?) = case.also {
            store[it.id] = it
            event?.let(events::add)
        }
        override suspend fun updateWithUboObservation(
            case: BusinessOnboardingCase,
            finding: UboFinding,
            recordedAt: Instant,
        ): UboObservation {
            store[case.id] = case
            return UboObservation(
                UUID.randomUUID(),
                case.id,
                observations.count { it.caseId == case.id }.toLong() + 1,
                finding,
                "0".repeat(64),
                recordedAt,
            ).also(observations::add)
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
            this.documents = docs
            this.settings = object : BusinessOnboardingSettings {
                override val highRiskCountries = setOf("IR", "KP")
                override val businessProduct = AgreementProduct("prod-004", "Business Current Account", "EUR")
                override fun legalFormLabel(country: String?, legalFormCode: String?, lang: String) =
                    "společnost s ručením omezeným"
            }
            this.ubo = object : BeneficialOwnershipPort {
                override suspend fun lookup(identifier: LegalEntityIdentifier) = UboFinding(
                    identifier,
                    UboSource.REGISTER,
                    uboOwners.toList(),
                    emptyList(),
                    0.25,
                    null,
                    null,
                    now,
                )
            }
        }
        coEvery { parties.pepProfile(any()) } answers { PepProfile(pepOnFile[firstArg()], null) }
    }

    // --- business agreement plumbing ----------------------------------------------------------

    private val uboOwners = mutableListOf<BeneficialOwner>()
    private val pepOnFile = mutableMapOf<UUID, Boolean>()

    /** document-service double: one agreement per case, SIGNED only for parties a test signs for. */
    private inner class FakeDocuments : DocumentGateway {
        val views = mutableMapOf<UUID, BusinessAgreementView>()
        val signed = mutableMapOf<UUID, MutableSet<UUID>>()
        var requests = mutableListOf<BusinessAgreementRequest>()
        var foreignCase: UUID? = null

        override suspend fun ensureBusinessAgreement(request: BusinessAgreementRequest): BusinessAgreementView {
            requests += request
            return view(
                views.getOrPut(request.caseId) {
                    BusinessAgreementView(
                        caseId = request.caseId,
                        documentId = UUID.randomUUID(),
                        templateCode = "RAMCOVA_SMLOUVA_PO_CS",
                        templateVersion = "1.0.0",
                        sha256 = "a".repeat(64),
                        ceremonyId = UUID.randomUUID(),
                        ceremonyStatus = CeremonyStatus.PENDING,
                        signers = request.signers.map { CeremonySigner(it.partyRef!!, CeremonySignerStatus.PENDING) },
                        disclosures = listOf(
                            AgreementDisclosure("VOP_CS", "1.1.0", "VOP", "b".repeat(64)),
                            AgreementDisclosure("SAZEBNIK_PO_CS", "1.0.0", "Sazebník", "c".repeat(64)),
                        ),
                    )
                },
            )
        }

        override suspend fun businessAgreement(caseId: UUID, lang: String) = views[caseId]?.let(::view)

        private fun view(v: BusinessAgreementView) = v.copy(
            caseId = foreignCase ?: v.caseId,
            signers = v.signers.map {
                if (it.partyRef in
                    signed[v.caseId].orEmpty()
                ) {
                    it.copy(status = CeremonySignerStatus.SIGNED)
                } else {
                    it
                }
            },
        )

        fun sign(caseId: UUID, party: UUID) {
            signed.getOrPut(caseId) { mutableSetOf() } += party
        }

        fun current(caseId: UUID) = views.getValue(caseId).disclosures.map {
            AcceptedDisclosure(it.code, it.version, it.sha256)
        }
    }

    private val docs = FakeDocuments()

    private fun questionnaire(
        countries: List<String> = listOf("CZ", "DE"),
        taxResidencies: List<String> = listOf("CZ"),
        fatca: FatcaStatus = FatcaStatus.ACTIVE_NFFE,
        cash: Boolean = false,
    ) = Questionnaire(
        purpose = RelationshipPurpose.OPERATING_ACCOUNT,
        expectedMonthlyTurnover = ExpectedTurnover.UP_TO_1M,
        sourceOfFunds = setOf(SourceOfFunds.BUSINESS_REVENUE),
        cashIntensive = cash,
        countries = countries,
        taxResidencies = taxResidencies,
        fatcaStatus = fatca,
        crsStatus = CrsStatus.ACTIVE_NFE,
    )

    private suspend fun cleanDeclarations(caseId: UUID) = Declarations(
        uboConfirmed = true,
        peps = (service.get(caseId).extract!!.representatives.map { it.fullName } + uboOwners.map { it.fullName })
            .map { PepEntry(it, false) },
        truthful = true,
    )

    /** Questionnaire, declarations, agreement and every identified signer's acceptance. Returns the ceremony id. */
    private suspend fun readyToSign(caseId: UUID, by: UUID, q: Questionnaire = questionnaire()): String {
        service.answerQuestionnaire(AnswerQuestionnaireCommand(caseId, by, q))
        service.makeDeclarations(MakeDeclarationsCommand(caseId, by, cleanDeclarations(caseId)))
        val view = service.prepareAgreement(PrepareAgreementCommand(caseId, by, "cs"))
        service.get(caseId).signers.mapNotNull { it.partyId }.forEach {
            service.acceptDisclosures(AcceptDisclosuresCommand(caseId, it, docs.current(caseId)))
        }
        return view.ceremonyId.toString()
    }

    /** The party completes the SCA ceremony in document-service, then reports it to kyb. */
    private suspend fun signs(caseId: UUID, party: UUID, ceremony: String): BusinessOnboardingCase {
        docs.sign(caseId, party)
        return service.sign(SignCommand(caseId, party, ceremony))
    }

    private suspend fun soleCaseReadyToSign(): UUID {
        coEvery { registry.lookup(ico, null) } returns extract(RepresentationRule.SOLE, listOf("Jana Nováková"))
        coEvery { parties.createEntityParty(any()) } returns entityParty
        coEvery { parties.grantMandate(any()) } returns Unit
        val started = service.start(StartCaseCommand(IdentifierScheme.CZ_ICO, "45274649", initiator))
        coEvery { parties.initiatorIdentity(any()) } returns InitiatorIdentity("Jana Nováková", null, verified = true)
        val ready = service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Nováková", null))
        assertThat(ready.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        return started.id
    }

    private fun conflictCode(block: suspend () -> Unit): String {
        val e = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertThat(e).isInstanceOf(AgreementConflictException::class.java)
        return (e as AgreementConflictException).code
    }

    @Test
    fun `the agreement cannot be prepared before the questionnaire and the declarations`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        assertThat(conflictCode { service.prepareAgreement(PrepareAgreementCommand(id, initiator, "cs")) })
            .isEqualTo(AgreementConflictException.PREREQUISITES_MISSING)
        service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
        assertThat(conflictCode { service.prepareAgreement(PrepareAgreementCommand(id, initiator, "cs")) })
            .isEqualTo(AgreementConflictException.PREREQUISITES_MISSING)
        service.makeDeclarations(MakeDeclarationsCommand(id, initiator, cleanDeclarations(id)))
        val view = service.prepareAgreement(PrepareAgreementCommand(id, initiator, "cs"))
        assertThat(store[id]!!.agreement!!.ceremonyId).isEqualTo(view.ceremonyId)
        val req = docs.requests.single()
        assertThat(req.product.code).isEqualTo("prod-004")
        assertThat(req.signers.map { it.partyRef }).containsExactly(initiator)
        assertThat(req.entity.ico).isEqualTo("45274649")
        assertThatThrownBy { runBlocking { service.prepareAgreement(PrepareAgreementCommand(id, initiator, "de")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only the initiator or a signer may answer, and a stranger is refused`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        assertThatThrownBy {
            runBlocking {
                service.answerQuestionnaire(AnswerQuestionnaireCommand(id, UUID.randomUUID(), questionnaire()))
            }
        }.isInstanceOf(CaseCallerMismatchException::class.java)
    }

    @Test
    fun `accepting a disclosure set that differs from document-service's is a stale 409`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
        service.makeDeclarations(MakeDeclarationsCommand(id, initiator, cleanDeclarations(id)))
        service.prepareAgreement(PrepareAgreementCommand(id, initiator, "cs"))
        val current = docs.current(id)
        listOf(
            current.drop(1),
            current.map { it.copy(sha256 = "d".repeat(64)) },
            current + current.first(),
        ).forEach { wrong ->
            assertThat(conflictCode { service.acceptDisclosures(AcceptDisclosuresCommand(id, initiator, wrong)) })
                .isEqualTo(AgreementConflictException.DISCLOSURES_STALE)
        }
        val accepted = service.acceptDisclosures(AcceptDisclosuresCommand(id, initiator, current.reversed()))
        assertThat(accepted.agreement!!.acceptedByParty(initiator)).isTrue()
    }

    @Test
    fun `signing without acceptance, with a foreign ref, unsigned, or on another case's ceremony is refused`(): Unit =
        runBlocking {
            val id = soleCaseReadyToSign()
            service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
            service.makeDeclarations(MakeDeclarationsCommand(id, initiator, cleanDeclarations(id)))
            val ceremony = service.prepareAgreement(PrepareAgreementCommand(id, initiator, "cs")).ceremonyId.toString()
            docs.sign(id, initiator)

            assertThat(conflictCode { service.sign(SignCommand(id, initiator, ceremony)) })
                .isEqualTo(AgreementConflictException.DISCLOSURES_NOT_ACCEPTED)
            service.acceptDisclosures(AcceptDisclosuresCommand(id, initiator, docs.current(id)))

            assertThat(conflictCode { service.sign(SignCommand(id, initiator, "cer-1")) })
                .isEqualTo(AgreementConflictException.SIGNATURE_REF_MISMATCH)

            docs.signed.clear()
            assertThat(conflictCode { service.sign(SignCommand(id, initiator, ceremony)) })
                .isEqualTo(AgreementConflictException.CEREMONY_NOT_SIGNED)

            docs.sign(id, initiator)
            docs.foreignCase = UUID.randomUUID()
            assertThat(conflictCode { service.sign(SignCommand(id, initiator, ceremony)) })
                .isEqualTo(AgreementConflictException.CEREMONY_CASE_MISMATCH)
            docs.foreignCase = null

            assertThat(store[id]!!.signedCount).isZero()
            val signed = service.sign(SignCommand(id, initiator, ceremony))
            assertThat(signed.status).isEqualTo(CaseStatus.SIGNED)
        }

    @Test
    fun `the happy path signs and binds the mandate evidence to the ceremony`(): Unit = runBlocking {
        val mandate = slot<MandateRequest>()
        val id = soleCaseReadyToSign()
        coEvery { parties.grantMandate(capture(mandate)) } returns Unit
        val ceremony = readyToSign(id, initiator)
        val signed = signs(id, initiator, ceremony)
        assertThat(signed.status).isEqualTo(CaseStatus.SIGNED)
        assertThat(signed.reviewReason).isNull()
        assertThat(mandate.captured.evidenceRef).isEqualTo("kyb-case:$id:signature:$ceremony")
    }

    @Test
    fun `a risk flag sends the case to MANUAL_REVIEW after the last signature, and review completes it`(): Unit =
        runBlocking {
            val mandates = mutableListOf<MandateRequest>()
            val id = soleCaseReadyToSign()
            coEvery { parties.grantMandate(capture(mandates)) } returns Unit
            val ceremony = readyToSign(id, initiator, questionnaire(countries = listOf("CZ", "IR")))
            val signed = signs(id, initiator, ceremony)
            assertThat(signed.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
            assertThat(signed.reviewReason).contains("high-risk countries: IR")
            assertThat(signed.signers.single().status).isEqualTo(SignerStatus.SIGNED)
            assertThat(mandates).describedAs("activation waits for the reviewer").isEmpty()
            assertThat(events.last().eventType).isEqualTo(KybEvents.REVIEW_REQUIRED)

            val resolved = service.resolveReview(ResolveReviewCommand(id, 1, "operator-anna"))
            assertThat(resolved.status).isEqualTo(CaseStatus.SIGNED)
            assertThat(mandates).hasSize(1)
        }

    @Test
    fun `a PEP on the customer profile needs no declaration and is a risk flag`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        coEvery { parties.grantMandate(any()) } returns Unit
        pepOnFile[initiator] = true
        service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
        val made = service.makeDeclarations(
            MakeDeclarationsCommand(id, initiator, Declarations(true, null, emptyList(), true)),
        )
        assertThat(made.declarations!!.peps.single().partyId).isEqualTo(initiator)
        assertThat(made.riskFlags(emptySet())).anyMatch { it.startsWith("PEP declared") }
    }

    @Test
    fun `a declaration contradicting the profile keeps the profile and flags the contradiction`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        pepOnFile[initiator] = false
        service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
        val made = service.makeDeclarations(
            MakeDeclarationsCommand(
                id,
                initiator,
                Declarations(true, null, listOf(PepEntry("Nováková Jana", true, "poslankyně")), true),
            ),
        )
        assertThat(made.declarations!!.peps.single().isPep).isFalse()
        assertThat(made.riskFlags(emptySet()))
            .containsExactly("PEP declaration differs from customer profile: Nováková Jana")
    }

    @Test
    fun `every reportable natural-person UBO needs a PEP entry`(): Unit = runBlocking {
        val id = soleCaseReadyToSign()
        service.answerQuestionnaire(AnswerQuestionnaireCommand(id, initiator, questionnaire()))
        val declarations = cleanDeclarations(id)
        uboOwners +=
            BeneficialOwner("Karel Vlastník", null, "CZ", "CZ", OwnershipBand.PCT_50_TO_75, emptyList(), null, false)
        uboOwners +=
            BeneficialOwner("Holding a.s.", null, null, "CZ", OwnershipBand.PCT_25_TO_50, emptyList(), null, true)
        assertThatThrownBy {
            runBlocking { service.makeDeclarations(MakeDeclarationsCommand(id, initiator, declarations)) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Karel Vlastník")
            .hasMessageNotContaining("Holding")
        assertThat(observations).isEmpty()
        service.makeDeclarations(MakeDeclarationsCommand(id, initiator, cleanDeclarations(id)))
        assertThat(observations).hasSize(1)
        assertThat(observations.single().finding.owners).hasSize(2)
    }

    @Test
    fun `the prefill offers the caller's questionnaire from another case and the known persons`(): Unit = runBlocking {
        val other = BusinessOnboardingCase.start(UUID.randomUUID(), ico, initiator, now.minusSeconds(3600)).copy(
            status = CaseStatus.ACTIVE,
            questionnaire = questionnaire(countries = listOf("SK")).copy(answeredBy = initiator, answeredAt = now),
        )
        store[other.id] = other
        val id = soleCaseReadyToSign()
        pepOnFile[initiator] = false
        val prefill = service.questionnairePrefill(id, initiator)
        assertThat(prefill.previousQuestionnaire!!.countries).containsExactly("SK")
        assertThat(prefill.knownPersons.single().pep).isFalse()
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
            coEvery { parties.initiatorIdentity(any()) } returns
                InitiatorIdentity("Jana Nováková", null, verified = true)
            service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Nováková", null))
            val invited = service.inviteCosigners(InviteCosignersCommand(started.id, initiator, listOf(1)))
            val token = invited.signers.first { !it.isInitiator }.invitationToken!!
            assertThat(events.map { it.eventType }).contains(KybEvents.SIGNER_INVITED)

            val cosigner = UUID.randomUUID()
            val claimed = service.claimInvitation(ClaimInvitationCommand(token, cosigner))
            assertThat(claimed.status).isEqualTo(CaseStatus.READY_TO_SIGN)

            val ceremony = readyToSign(started.id, initiator)
            assertThat(docs.requests.last().signers.map { it.partyRef }).containsExactlyInAnyOrder(initiator, cosigner)
            val first = signs(started.id, initiator, ceremony)
            assertThat(first.status).isEqualTo(CaseStatus.READY_TO_SIGN) // JOINT: one of two is not enough
            assertThat(mandates).isEmpty() // one signature of two: nothing is granted yet
            val signed = signs(started.id, cosigner, ceremony)
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
                coEvery { parties.initiatorIdentity(any()) } returns
                    InitiatorIdentity("Jan Novák", null, verified = true)
                service.matchInitiator(MatchInitiatorCommand(started.id, UUID.randomUUID(), 0, "Jan Novák", null))
            }
        }
            .isInstanceOf(CaseCallerMismatchException::class.java)
        coEvery { parties.initiatorIdentity(any()) } returns InitiatorIdentity("Jan Novák", null, verified = true)
        val ready = service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jan Novák", null))
        assertThat(ready.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        signs(started.id, initiator, readyToSign(started.id, initiator))
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
        coEvery { parties.initiatorIdentity(any()) } returns InitiatorIdentity("Jana Chairová", null, verified = true)
        service.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Chairová", null))
        val invited = service.inviteCosigners(InviteCosignersCommand(started.id, initiator, listOf(1, 2)))
        // Everyone identifies, so the offices ARE covered and signing opens.
        invited.signers.filter { !it.isInitiator }.forEach {
            service.claimInvitation(ClaimInvitationCommand(it.invitationToken!!, UUID.randomUUID()))
        }
        val member = service.get(started.id).signers.first { it.fullName == "Milan Member" }.partyId!!

        // The vice never signs: chair + ordinary member reach the COUNT and miss an office.
        val ceremony = readyToSign(started.id, initiator)
        signs(started.id, initiator, ceremony)
        val shortfall = signs(started.id, member, ceremony)
        assertThat(shortfall.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(mandates).describedAs("nothing is granted while the offices are short").isEmpty()

        // The operator accepts the two collected signatures, dropping the office constraint.
        val accepted = service.resolveReview(ResolveReviewCommand(started.id, 2, "operator-anna"))

        assertThat(accepted.status).isIn(CaseStatus.SIGNED, CaseStatus.ACTIVE)
        assertThat(mandates)
            .describedAs("an entity that reaches SIGNED must carry a mandate per signature")
            .hasSize(2)
    }

    // --- fully digital onboarding of the sandbox demo company (single jednatel, SOLE rule) ------

    private val demoIco = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, DemoEntity.ICO)
    private val demoAddress = RegisteredAddress("Ukázková 1", "Praha", "11000", "CZ")

    /** The same service, but with the REAL attestation service — no pre-confirmation. */
    private fun withRealAttestation(): BusinessOnboardingService {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val demo = DemoEntity(true, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", clock)
        coEvery { registry.lookup(demoIco, null) } returns demo.extract()
        coEvery { parties.createEntityParty(any()) } returns entityParty
        val saved = mutableListOf<RepresentationAttestation>()
        val attestationStore = object : RepresentationAttestationRepository {
            override suspend fun findActive(identifier: LegalEntityIdentifier, ruleTextHash: String) =
                saved.firstOrNull { it.identifier == identifier && it.ruleTextHash == ruleTextHash }
            override suspend fun findLatestFor(identifier: LegalEntityIdentifier) =
                saved.lastOrNull { it.identifier == identifier }
            override suspend fun attest(attestation: RepresentationAttestation) = attestation.also(saved::add)
            override suspend fun listFor(identifier: LegalEntityIdentifier) = saved.toList()
        }
        val lookup = service.lookup
        service.representation = RepresentationAttestationService().apply {
            this.attestations = attestationStore
            this.lookup = lookup
            this.clock = clock
            this.autoConfirmSingleMember = Optional.of(true)
        }
        return service
    }

    @Test
    fun `the demo company verifies without review and its jednatel at the same address is ready to sign`(): Unit =
        runBlocking {
            val svc = withRealAttestation()
            val started = svc.start(StartCaseCommand(IdentifierScheme.CZ_ICO, DemoEntity.ICO, initiator))
            assertThat(started.status).isEqualTo(CaseStatus.REGISTRY_VERIFIED)
            assertThat(started.requiredSignatures).isEqualTo(1)

            coEvery { parties.initiatorIdentity(any()) } returns
                InitiatorIdentity("Oldřich Vaněk", demoAddress, verified = true)
            val matched = svc.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Oldřich Vaněk", null))
            assertThat(matched.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        }

    @Test
    fun `the demo jednatel at a different postal code goes to review`(): Unit = runBlocking {
        val svc = withRealAttestation()
        val started = svc.start(StartCaseCommand(IdentifierScheme.CZ_ICO, DemoEntity.ICO, initiator))
        coEvery { parties.initiatorIdentity(any()) } returns
            InitiatorIdentity("Oldřich Vaněk", demoAddress.copy(postalCode = "60200"), verified = true)
        val matched = svc.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Oldřich Vaněk", null))
        assertThat(matched.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
    }

    @Test
    fun `someone other than the demo jednatel is refused`(): Unit = runBlocking {
        val svc = withRealAttestation()
        val started = svc.start(StartCaseCommand(IdentifierScheme.CZ_ICO, DemoEntity.ICO, initiator))
        coEvery { parties.initiatorIdentity(any()) } returns
            InitiatorIdentity("Jana Nováková", demoAddress, verified = true)
        assertThatThrownBy {
            runBlocking { svc.matchInitiator(MatchInitiatorCommand(started.id, initiator, 0, "Jana Nováková", null)) }
        }.isInstanceOf(InitiatorIdentityMismatchException::class.java)
    }
}
