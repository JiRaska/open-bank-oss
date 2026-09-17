// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.AgreementEntity
import com.openbank.document.application.port.`in`.AgreementProduct
import com.openbank.document.application.port.`in`.AgreementRepresentative
import com.openbank.document.application.port.`in`.AgreementSigner
import com.openbank.document.application.port.`in`.BusinessAgreementConflictException
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.EnsureBusinessAgreementCommand
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.application.port.out.ProductFee
import com.openbank.document.application.port.out.ProductFeeSchedule
import com.openbank.document.application.port.out.ProductInfo
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.DocumentTemplateSeed
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The business agreement's lifecycle rules, driven through in-memory ports so the state the
 * service reads back is the state it wrote (a mock returning canned values could not show that
 * a replacement released the idempotency key, or that a signed agreement stays put).
 */
class BusinessAgreementServiceTest {

    private val docs = InMemoryDocuments()
    private val ceremonies = InMemoryCeremonies(docs)
    private val renders = RecordingRenderer(docs)
    private val catalog = StubCatalog()
    private val service = BusinessAgreementService(
        renderUseCase = renders,
        ceremonyUseCase = ceremonies,
        documentRepo = docs,
        ceremonyRepo = ceremonies,
        templateRepo = SeedTemplates(),
        productCatalogPort = catalog,
        clock = Clock.fixed(Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC),
        objectMapper = ObjectMapper(),
    )

    private val caseId: UUID = UUID.fromString("3f2a9c1d-0b4e-4c55-9a11-000000000001")
    private val entityId: UUID = UUID.fromString("3f2a9c1d-0b4e-4c55-9a11-0000000000e1")
    private val alice = "3f2a9c1d-0b4e-4c55-9a11-00000000000a"
    private val bob = "3f2a9c1d-0b4e-4c55-9a11-00000000000b"

    private fun cmd(
        signers: List<AgreementSigner> = listOf(
            AgreementSigner(alice, "Petr Horák", "jednatel"),
            AgreementSigner(bob, "Eva Horáková", "jednatelka"),
        ),
        lang: String = "cs",
        signingRule: String = "Za společnost jednají dva jednatelé společně.",
    ) = EnsureBusinessAgreementCommand(
        caseId = caseId,
        entityPartyId = entityId,
        lang = lang,
        entity = AgreementEntity("Stavby Horák s.r.o.", "27074358", "Jindřišská 16, 110 00 Praha 1", "s.r.o."),
        representatives = listOf(AgreementRepresentative(alice, "Petr Horák", "jednatel")),
        signers = signers,
        signingRule = signingRule,
        product = AgreementProduct("prod-004", "Business Current Account", "EUR"),
    )

    @Test
    fun `renders agreement and four disclosures for entity and case, signers in request order`(): Unit = runBlocking {
        val agreement = service.ensure(cmd())

        assertThat(agreement.templateCode).isEqualTo("RAMCOVA_SMLOUVA_PO_CS")
        assertThat(agreement.templateVersion).isEqualTo("1.0.0")
        assertThat(agreement.ceremonyStatus).isEqualTo(CeremonyStatus.PENDING)
        assertThat(agreement.signers.map { it.partyRef }).containsExactly(alice, bob)
        assertThat(ceremonies.opened.single().signerPartyRefs).containsExactly(alice, bob)
        assertThat(ceremonies.opened.single().signatureLevel).isEqualTo(SignatureLevel.ADVANCED)
        assertThat(agreement.disclosures.map { it.code })
            .containsExactly(
                "VOP_CS",
                "SAZEBNIK_PO_CS",
                "PREDSMLUVNI_INFORMACE_PO_CS",
                "INFORMACE_POJISTENI_VKLADU_CS",
            )
        assertThat(agreement.disclosures.first().title).isEqualTo("Všeobecné obchodní podmínky")
        assertThat(agreement.disclosures.first().version).isEqualTo("1.1.0")
        assertThat(agreement.sealedSha256).isNull()

        val stored = docs.all()
        assertThat(stored).hasSize(5)
        assertThat(stored).allSatisfy { d ->
            assertThat(d.partyRef).isEqualTo(entityId.toString())
            assertThat(d.caseRef).isEqualTo(caseId.toString())
        }
        assertThat(docs.byId(agreement.documentId)!!.status).isEqualTo(DocumentStatus.PENDING_SIGNATURE)

        // The agreement lists its annexes and signers; the fee schedule carries catalogue data.
        val agreementData = renders.dataFor("RAMCOVA_SMLOUVA_PO_CS")
        @Suppress("UNCHECKED_CAST")
        assertThat((agreementData["disclosures"] as List<Map<String, Any?>>).map { it["code"] })
            .containsExactly(
                "VOP_CS",
                "SAZEBNIK_PO_CS",
                "PREDSMLUVNI_INFORMACE_PO_CS",
                "INFORMACE_POJISTENI_VKLADU_CS",
            )
        @Suppress("UNCHECKED_CAST")
        assertThat((agreementData["signers"] as List<Map<String, Any?>>).map { it["name"] })
            .containsExactly("Petr Horák", "Eva Horáková")
        @Suppress("UNCHECKED_CAST")
        val fees = renders.dataFor("SAZEBNIK_PO_CS")["fees"] as List<Map<String, Any?>>
        assertThat(fees.map { it["amount"] }).containsExactly("19,99 EUR", "0,25 EUR", "15 EUR", "0,5 %", "2,5 EUR")
        assertThat(fees.map { it["label"] }).containsExactly(
            "Vedení účtu",
            "Odchozí platba SEPA",
            "Odchozí zahraniční platba SWIFT",
            "Vklad hotovosti",
            "Vedení platební karty (za kartu)",
        )
        // Nothing English reaches a Czech fee schedule: no catalogue name, no description.
        val catalogueText = catalog.schedule!!.fees.flatMap { listOfNotNull(it.name, it.description) }
        assertThat(fees.flatMap { it.values }.map { it.toString() }).doesNotContainAnyElementsOf(catalogueText)
        assertThat(fees.map { it["frequency"] })
            .containsExactly("měsíčně", "za transakci", "za transakci", "z částky transakce", "měsíčně")
    }

    @Test
    fun `the same request is idempotent - no re-render, same document, same ceremony, same disclosures`(): Unit =
        runBlocking {
            val first = service.ensure(cmd())
            val second = service.ensure(cmd())

            assertThat(second).isEqualTo(first)
            assertThat(renders.count).isEqualTo(5)
            assertThat(ceremonies.opened).hasSize(1)
        }

    @Test
    fun `a changed request replaces a still-pending agreement and withdraws its ceremony`(): Unit = runBlocking {
        val first = service.ensure(cmd())
        val second = service.ensure(cmd(signingRule = "Za společnost jedná každý jednatel samostatně."))

        assertThat(second.documentId).isNotEqualTo(first.documentId)
        assertThat(second.ceremonyId).isNotEqualTo(first.ceremonyId)
        assertThat(docs.byId(first.documentId)!!.status).isEqualTo(DocumentStatus.ARCHIVED)
        assertThat(ceremonies.byId(first.ceremonyId)!!.status).isEqualTo(CeremonyStatus.EXPIRED)
        // Disclosures are not re-rendered: the set a customer accepts is stable across calls.
        assertThat(second.disclosures).isEqualTo(first.disclosures)
        assertThat(renders.count).isEqualTo(6)
    }

    @Test
    fun `a dropped signer replaces the pending ceremony with one whose signers match the request`(): Unit =
        runBlocking {
            service.ensure(cmd())
            val onlyAlice = service.ensure(cmd(signers = listOf(AgreementSigner(alice, "Petr Horák", "jednatel"))))

            assertThat(onlyAlice.signers.map { it.partyRef }).containsExactly(alice)
            assertThat(ceremonies.byId(onlyAlice.ceremonyId)!!.signers.map { it.partyRef }).containsExactly(alice)
        }

    @Test
    fun `once any signer has signed a changed request is a conflict and nothing is replaced`(): Unit = runBlocking {
        val first = service.ensure(cmd())
        ceremonies.sign(first.ceremonyId, alice)

        assertThatThrownBy { runBlocking { service.ensure(cmd(signingRule = "changed")) } }
            .isInstanceOf(BusinessAgreementConflictException::class.java)
        assertThat(docs.byId(first.documentId)!!.status).isEqualTo(DocumentStatus.PENDING_SIGNATURE)
        assertThat(ceremonies.byId(first.ceremonyId)!!.status).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)

        // The unchanged request still answers with the partially signed agreement.
        assertThat(service.ensure(cmd()).ceremonyStatus).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)
    }

    @Test
    fun `a signed agreement is never replaced - neither by a changed request nor by the other language`(): Unit =
        runBlocking {
            val first = service.ensure(cmd())
            ceremonies.sign(first.ceremonyId, alice)
            ceremonies.sign(first.ceremonyId, bob)
            docs.save(docs.byId(first.documentId)!!.markSigned().sealed("ab".repeat(32)))

            assertThatThrownBy { runBlocking { service.ensure(cmd(signingRule = "changed")) } }
                .isInstanceOf(BusinessAgreementConflictException::class.java)
            assertThatThrownBy { runBlocking { service.ensure(cmd(lang = "en")) } }
                .isInstanceOf(BusinessAgreementConflictException::class.java)

            val same = service.ensure(cmd())
            assertThat(same.documentId).isEqualTo(first.documentId)
            assertThat(same.ceremonyStatus).isEqualTo(CeremonyStatus.COMPLETED)
            assertThat(same.sha256).isEqualTo(first.sha256)
            assertThat(same.sealedSha256).isEqualTo("ab".repeat(32))
        }

    @Test
    fun `switching language supersedes the unsigned agreement in the other language`(): Unit = runBlocking {
        val cs = service.ensure(cmd())
        val en = service.ensure(cmd(lang = "en"))

        assertThat(en.templateCode).isEqualTo("RAMCOVA_SMLOUVA_PO_EN")
        assertThat(en.disclosures.map { it.code }).allSatisfy { assertThat(it).endsWith("_EN") }
        assertThat(ceremonies.byId(cs.ceremonyId)!!.status).isEqualTo(CeremonyStatus.EXPIRED)
        assertThat(service.find(caseId, "cs")).isNull()
        assertThat(service.find(caseId, null)!!.documentId).isEqualTo(en.documentId)
    }

    @Test
    fun `a declined ceremony is re-issued on the next request`(): Unit = runBlocking {
        val first = service.ensure(cmd())
        ceremonies.decline(first.ceremonyId, alice)

        val again = service.ensure(cmd())

        assertThat(again.ceremonyId).isNotEqualTo(first.ceremonyId)
        assertThat(again.ceremonyStatus).isEqualTo(CeremonyStatus.PENDING)
    }

    @Test
    fun `find returns null for an unknown case and the stored agreement otherwise`(): Unit = runBlocking {
        assertThat(service.find(caseId, "cs")).isNull()
        val created = service.ensure(cmd())
        assertThat(service.find(caseId, "cs")).isEqualTo(created)
    }

    @Test
    fun `invalid requests are rejected before anything is rendered`() {
        listOf(
            cmd(signers = emptyList()),
            cmd(signers = listOf(AgreementSigner(alice, "A", "r"), AgreementSigner(alice, "A", "r"))),
            cmd(signers = listOf(AgreementSigner("not-a-uuid", "A", "r"))),
            cmd(lang = "de"),
            cmd(signingRule = " "),
        ).forEach { bad ->
            assertThatThrownBy {
                runBlocking { service.ensure(bad) }
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(renders.count).isZero()
    }

    @Test
    fun `no fee data in the catalogue means no fee schedule and no agreement`() {
        catalog.schedule = null
        assertThatThrownBy { runBlocking { service.ensure(cmd()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("fee schedule")
        assertThat(docs.all().map { it.templateCode }).doesNotContain("RAMCOVA_SMLOUVA_PO_CS")
    }

    @Test
    fun `the english fee schedule uses english labels`(): Unit = runBlocking {
        service.ensure(cmd(lang = "en"))
        @Suppress("UNCHECKED_CAST")
        val fees = renders.dataFor("SAZEBNIK_PO_EN")["fees"] as List<Map<String, Any?>>
        assertThat(fees.map { it["label"] }).first().isEqualTo("Account maintenance")
        assertThat(fees.map { it["amount"] }).first().isEqualTo("EUR 19.99")
    }

    @Test
    fun `a catalogue fee with no label in the requested language refuses the fee schedule`() {
        catalog.schedule = catalog.schedule!!.copy(
            fees =
            catalog.schedule!!.fees + ProductFee("Paper Statement", BigDecimal("1"), "EUR", "MONTHLY", null, null),
        )
        assertThatThrownBy { runBlocking { service.ensure(cmd()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No cs label for product-catalogue fee 'Paper Statement'")
        assertThat(docs.all().map { it.templateCode }).doesNotContain("SAZEBNIK_PO_CS", "RAMCOVA_SMLOUVA_PO_CS")
    }

    @Test
    fun `joint signers may sign in any order and the ceremony completes only when all have signed`(): Unit =
        runBlocking {
            val agreement = service.ensure(cmd())
            assertThat(ceremonies.opened.single().parallel).isTrue()

            // bob is second in the request, and signs first.
            ceremonies.sign(agreement.ceremonyId, bob)
            assertThat(ceremonies.byId(agreement.ceremonyId)!!.status).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)

            ceremonies.sign(agreement.ceremonyId, alice)
            assertThat(service.find(caseId, "cs")!!.ceremonyStatus).isEqualTo(CeremonyStatus.COMPLETED)
        }

    // ── in-memory ports ─────────────────────────────────────────────────────────────────────────

    private class InMemoryDocuments : DocumentRepositoryPort {
        private val rows = linkedMapOf<UUID, Document>()
        fun all() = rows.values.filter { it.status != DocumentStatus.ARCHIVED }
        fun byId(id: UUID) = rows[id]
        override suspend fun save(document: Document) = document.also { rows[it.id] = it }
        override suspend fun saveWithOutbox(document: Document, outboxMessage: OutboxMessage) = save(document)
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByParty(partyRef: String) = rows.values.filter { it.partyRef == partyRef }
        override suspend fun findByPartyPaged(partyRef: String, page: Int, size: Int) = findByParty(partyRef)
        override suspend fun countByParty(partyRef: String) = findByParty(partyRef).size.toLong()
        override suspend fun findByIdempotencyKey(idempotencyKey: String) =
            rows.values.firstOrNull { it.idempotencyKey == idempotencyKey }
    }

    private class RecordingRenderer(private val docs: InMemoryDocuments) : DocumentRenderUseCase {
        private val calls = mutableListOf<RenderDocumentCommand>()
        val count get() = calls.size
        fun dataFor(code: String) = calls.last { it.templateCode == code }.data

        override suspend fun render(cmd: RenderDocumentCommand): Document {
            calls += cmd
            val template = DocumentTemplateSeed.templates.single { it.code == cmd.templateCode }
            val id = UUID.randomUUID()
            return docs.save(
                Document(
                    id = id,
                    templateCode = template.code,
                    templateVersion = template.version,
                    sha256 = Document.sha256("${cmd.data}$id".toByteArray()),
                    storageKey = "documents/$id",
                    contentType = cmd.contentType,
                    sizeBytes = 1,
                    status = DocumentStatus.GENERATED,
                    metadata = cmd.metadata,
                    partyRef = cmd.partyRef,
                    caseRef = cmd.caseRef,
                    productRef = cmd.productRef,
                    retainUntil = null,
                    createdAt = Instant.EPOCH.plusSeconds(calls.size.toLong()),
                    idempotencyKey = cmd.idempotencyKey,
                ),
            )
        }
    }

    /** Opens ceremonies the way SignatureCeremonyService does, including the PENDING_SIGNATURE move. */
    private class InMemoryCeremonies(private val docs: InMemoryDocuments) :
        SignatureCeremonyUseCase,
        CeremonyRepositoryPort {
        private val rows = linkedMapOf<UUID, SignatureCeremony>()
        val opened = mutableListOf<OpenCeremonyCommand>()
        fun byId(id: UUID) = rows[id]

        fun sign(id: UUID, partyRef: String) {
            rows[id] = rows.getValue(id).recordDecision(partyRef, SignerStatus.SIGNED, Instant.EPOCH)
        }

        fun decline(id: UUID, partyRef: String) {
            rows[id] = rows.getValue(id).recordDecision(partyRef, SignerStatus.DECLINED, Instant.EPOCH)
        }

        override suspend fun openCeremony(cmd: OpenCeremonyCommand): SignatureCeremony {
            opened += cmd
            val doc = docs.findById(cmd.documentId)!!
            if (doc.status == DocumentStatus.GENERATED) docs.save(doc.markPendingSignature())
            val ceremony = SignatureCeremony(
                id = UUID.randomUUID(),
                documentId = cmd.documentId,
                signers = cmd.signerPartyRefs.mapIndexed { i, p -> Signer(p, i + 1, SignerStatus.PENDING, null) },
                status = CeremonyStatus.DRAFT,
                signatureLevel = cmd.signatureLevel,
                createdAt = Instant.EPOCH,
                parallel = cmd.parallel,
            ).open()
            return save(ceremony)
        }

        override suspend fun findByDocumentId(documentId: UUID) = rows.values.firstOrNull {
            it.documentId == documentId
        }
        override suspend fun recordDecision(
            ceremonyId: UUID,
            partyRef: String,
            decision: SignerStatus,
            evidenceRef: String?,
        ) = error("not used")
        override suspend fun getCeremony(id: UUID) = rows[id]
        override suspend fun save(ceremony: SignatureCeremony) = ceremony.also { rows[it.id] = it }
        override suspend fun saveWithOutbox(ceremony: SignatureCeremony, outboxMessage: OutboxMessage) = save(ceremony)
        override suspend fun findById(id: UUID) = rows[id]
    }

    private class SeedTemplates : TemplateRepositoryPort {
        private val seed = DocumentTemplateSeed.templates
        override suspend fun save(template: DocumentTemplate) = template
        override suspend fun findById(id: UUID) = seed.firstOrNull { it.id == id }
        override suspend fun findPublished(code: String, version: String) =
            seed.firstOrNull { it.code == code && it.version == version }
        override suspend fun findLatestPublished(code: String) = seed.firstOrNull { it.code == code }
        override suspend fun findAllTemplates(limit: Int) = seed.take(limit)
        override suspend fun publishReplacing(toPublish: DocumentTemplate, toRetire: DocumentTemplate?) = toPublish
    }

    private class StubCatalog : ProductCatalogPort {
        var schedule: ProductFeeSchedule? = ProductFeeSchedule(
            name = "Business Current Account",
            code = "CURRENT_BUSINESS",
            currency = "EUR",
            fees = listOf(
                // prod-004 "Business Current Account" exactly as product-catalog seeds it.
                ProductFee("Monthly Fee", BigDecimal("19.99"), "EUR", "MONTHLY", null, null),
                ProductFee("SEPA Transfer", BigDecimal("0.25"), "EUR", "PER_TRANSACTION", null, null),
                ProductFee("SWIFT Transfer", BigDecimal("15.0"), "EUR", "PER_TRANSACTION", null, null),
                ProductFee("Cash Deposit", BigDecimal("0.5"), "EUR", "PERCENTAGE", "0.5% of deposit amount", null),
                ProductFee("Card Fee (per card/month)", BigDecimal("2.50"), "EUR", "MONTHLY", null, null),
            ),
        )
        override suspend fun findDocumentTemplateCode(productId: UUID): String? = null
        override suspend fun findProduct(productId: UUID): ProductInfo? = null
        override suspend fun findFeeSchedule(productRef: String) = schedule
    }
}
