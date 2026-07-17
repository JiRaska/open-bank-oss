// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.AccountInfo
import com.openbank.document.application.port.out.AccountLookupPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.application.port.out.PartyInfo
import com.openbank.document.application.port.out.PartyLookupPort
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.application.port.out.ProductInfo
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0162 D7 onboarding wiring: idempotent + resumable under at-least-once Kafka delivery. The
 * idempotency key is keyed on the account, the document is rendered at most once for it, and the
 * ceremony is opened only if the document doesn't already have one (so a crash between the two
 * effects self-heals on replay).
 */
class OnboardingDocumentServiceTest {

    private val productCatalogPort: ProductCatalogPort = mockk()
    private val partyLookupPort: PartyLookupPort = mockk()
    private val accountLookupPort: AccountLookupPort = mockk()
    private val renderUseCase: DocumentRenderUseCase = mockk()
    private val documentQueryUseCase: DocumentQueryUseCase = mockk()
    private val ceremonyUseCase: SignatureCeremonyUseCase = mockk()
    private val documentRepository: DocumentRepositoryPort = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-17T10:00:00Z"), ZoneOffset.UTC)
    private val service = OnboardingDocumentService(
        productCatalogPort,
        partyLookupPort,
        accountLookupPort,
        renderUseCase,
        documentQueryUseCase,
        ceremonyUseCase,
        documentRepository,
        clock,
    )

    private val accountId: UUID = UUID.randomUUID()
    private val productId: UUID = UUID.randomUUID()

    // A non-UUID partyRef here is deliberate — it doubles as coverage for the "unparseable
    // partyRef" fail-open path (buildAgreementData never throws, just skips enrichment lookups)
    // instead of needing a separate test to exercise it.
    private val partyRef = "party-1"
    private val idempotencyKey = "onboarding:$accountId"

    @Test
    fun `renders the product's bound template with no pinned version and opens a ceremony`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        // buildAgreementData still resolves the product from productIdOverride even though
        // partyRef ("party-1") isn't a parseable UUID — party/account lookups are skipped, but
        // the product lookup doesn't depend on partyId.
        coEvery { productCatalogPort.findProduct(productId) } returns null
        val document = document()
        coEvery { renderUseCase.render(any()) } returns document
        coEvery { ceremonyUseCase.findByDocumentId(document.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        // Non-data fields asserted exactly; `data`'s content is covered by the dedicated
        // enrichment tests below, since it now depends on party/account/product lookups.
        coVerify(exactly = 1) {
            renderUseCase.render(
                match {
                    it.templateCode == "RAMCOVA_SMLOUVA_CS" &&
                        it.templateVersion == null &&
                        it.contentType == "application/pdf" &&
                        it.partyRef == partyRef &&
                        it.caseRef == accountId.toString() &&
                        it.productRef == productId.toString() &&
                        it.retainUntil == null &&
                        it.idempotencyKey == idempotencyKey
                },
            )
        }
        coVerify(exactly = 1) {
            ceremonyUseCase.openCeremony(
                OpenCeremonyCommand(
                    documentId = document.id,
                    signerPartyRefs = listOf(partyRef),
                    signatureLevel = SignatureLevel.ADVANCED,
                ),
            )
        }
    }

    @Test
    fun `skips render and ceremony when this account is already fully onboarded (idempotent replay)`(): Unit =
        runBlocking {
            val existing = document()
            coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns existing
            coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns mockk()

            service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

            coVerify(exactly = 0) { renderUseCase.render(any()) }
            coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
        }

    @Test
    fun `resumes by opening the ceremony when the document exists but has no ceremony yet`(): Unit = runBlocking {
        // A crash landed the document but not its ceremony; the replay must NOT re-render, but MUST
        // open the missing ceremony.
        val existing = document()
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns existing
        coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 1) { ceremonyUseCase.openCeremony(any()) }
    }

    @Test
    fun `does nothing when the product has no documentTemplateCode bound`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns null

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
    }

    @Test
    fun `reuses the winning document when a concurrent delivery lost the render race`(): Unit = runBlocking {
        val winner = document()
        // First lookup misses; render loses the unique-key race; the retry lookup finds the winner.
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null andThen winner
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        coEvery { productCatalogPort.findProduct(productId) } returns null
        coEvery { renderUseCase.render(any()) } throws DuplicateDocumentException("lost the race")
        coEvery { ceremonyUseCase.findByDocumentId(winner.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 1) {
            ceremonyUseCase.openCeremony(match { it.documentId == winner.id })
        }
    }

    @Test
    fun `tolerates a concurrent ceremony open (idempotent, no error propagates)`(): Unit = runBlocking {
        val document = document()
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        coEvery { productCatalogPort.findProduct(productId) } returns null
        coEvery { renderUseCase.render(any()) } returns document
        coEvery { ceremonyUseCase.findByDocumentId(document.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } throws DuplicateCeremonyException("lost the race")

        // Must not throw — the concurrent winner already opened the ceremony.
        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 1) { ceremonyUseCase.openCeremony(any()) }
    }

    // ── ensureOnboardingAgreement (ADR-0169 D3) ────────────────────────────────────────────────

    @Test
    fun `ensure renders a fresh agreement in the requested language and opens a ceremony`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        val rendered = document(code = "RAMCOVA_SMLOUVA_EN")
        coEvery { renderUseCase.render(any()) } returns rendered
        coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.ceremonyId).isEqualTo(ceremonyId)
        assertThat(result.documentId).isEqualTo(rendered.id)
        assertThat(result.templateCode).isEqualTo("RAMCOVA_SMLOUVA_EN")
        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_EN" }) }
    }

    @Test
    fun `ensure reuses a pending agreement already in the requested language, without re-rendering`(): Unit =
        runBlocking {
            val ceremonyId = UUID.randomUUID()
            val existing = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.PENDING_SIGNATURE)
            coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(existing)
            coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns ceremony(ceremonyId)

            val result = service.ensureOnboardingAgreement(partyRef, "cs")

            assertThat(result.documentId).isEqualTo(existing.id)
            assertThat(result.ceremonyId).isEqualTo(ceremonyId)
            coVerify(exactly = 0) { renderUseCase.render(any()) }
        }

    @Test
    fun `ensure returns an already-signed agreement untouched, whatever language was asked`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        val signed = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.SIGNED)
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(signed)
        coEvery { ceremonyUseCase.findByDocumentId(signed.id) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.documentId).isEqualTo(signed.id)
        assertThat(result.documentStatus).isEqualTo(DocumentStatus.SIGNED)
        coVerify(exactly = 0) { renderUseCase.render(any()) }
    }

    @Test
    fun `ensure supersedes a pending agreement in a different language and re-renders`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        val stale = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.PENDING_SIGNATURE)
        val fresh = document(code = "RAMCOVA_SMLOUVA_EN")
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(stale)
        coEvery { documentRepository.save(any()) } answers { firstArg() }
        coEvery { renderUseCase.render(any()) } returns fresh
        coEvery { ceremonyUseCase.findByDocumentId(fresh.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.templateCode).isEqualTo("RAMCOVA_SMLOUVA_EN")
        // the stale CS document is archived before the EN render
        coVerify(exactly = 1) { documentRepository.save(match { it.status == DocumentStatus.ARCHIVED }) }
        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_EN" }) }
    }

    @Test
    fun `ensure falls back to the default locale for an unsupported language`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        val rendered = document(code = "RAMCOVA_SMLOUVA_CS")
        coEvery { renderUseCase.render(any()) } returns rendered
        coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(UUID.randomUUID())

        service.ensureOnboardingAgreement(partyRef, "de")

        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_CS" }) }
    }

    // ── Template data enrichment — the fix: `data` used to always be emptyMap(), so a real
    // signed RAMCOVA_SMLOUVA read "(the "Customer")" with a blank address (ADR-0169 D5) ────────

    @Test
    fun `ensure fills party, account, product and document data into the rendered template`(): Unit = runBlocking {
        val realPartyRef = UUID.randomUUID()
        coEvery { documentQueryUseCase.listByParty(realPartyRef.toString()) } returns emptyList()
        coEvery { partyLookupPort.findById(realPartyRef) } returns
            PartyInfo(legalName = "Adéla Bartošová", formattedAddress = "Václavské náměstí 1, 110 00 Praha 1")
        val accountProductId = UUID.randomUUID()
        coEvery { accountLookupPort.findCurrentAccount(realPartyRef) } returns
            AccountInfo(iban = "CZ6508000000192000145399", productId = accountProductId)
        coEvery { productCatalogPort.findProduct(accountProductId) } returns
            ProductInfo(name = "CZK Current Account", code = "CURRENT_CZK")
        val rendered = document(code = "RAMCOVA_SMLOUVA_CS")
        coEvery { renderUseCase.render(any()) } returns rendered
        coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(UUID.randomUUID())

        service.ensureOnboardingAgreement(realPartyRef.toString(), "cs")

        coVerify(exactly = 1) {
            renderUseCase.render(
                match {
                    @Suppress("UNCHECKED_CAST")
                    val party = it.data["party"] as Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val account = it.data["account"] as Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val product = it.data["product"] as Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val doc = it.data["document"] as Map<String, Any?>
                    party["name"] == "Adéla Bartošová" &&
                        party["address"] == "Václavské náměstí 1, 110 00 Praha 1" &&
                        account["iban"] == "CZ6508000000192000145399" &&
                        product["name"] == "CZK Current Account" &&
                        product["code"] == "CURRENT_CZK" &&
                        doc["date"] == "2026-07-17" &&
                        doc["caseRef"] == "onboarding-agreement:$realPartyRef"
                },
            )
        }
    }

    @Test
    fun `ensure degrades to an unnamed party rather than failing when party-service is unreachable`(): Unit =
        runBlocking {
            val realPartyRef = UUID.randomUUID()
            coEvery { documentQueryUseCase.listByParty(realPartyRef.toString()) } returns emptyList()
            // PartyLookupPort/AccountLookupPort are themselves fail-open (never throw — see their
            // adapters), so "unreachable" surfaces here as null, not an exception.
            coEvery { partyLookupPort.findById(realPartyRef) } returns null
            coEvery { accountLookupPort.findCurrentAccount(realPartyRef) } returns null
            val rendered = document(code = "RAMCOVA_SMLOUVA_CS")
            coEvery { renderUseCase.render(any()) } returns rendered
            coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
            coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(UUID.randomUUID())

            // Must not throw — an enrichment-dependency outage degrades the render, never blocks it.
            service.ensureOnboardingAgreement(realPartyRef.toString(), "cs")

            coVerify(exactly = 1) {
                renderUseCase.render(
                    match {
                        @Suppress("UNCHECKED_CAST")
                        val party = it.data["party"] as Map<String, Any?>
                        party["name"] == "" && party["address"] == null
                    },
                )
            }
            // No account -> no product to look up.
            coVerify(exactly = 0) { productCatalogPort.findProduct(any()) }
        }

    private fun ceremony(id: UUID): SignatureCeremony = mockk { every { this@mockk.id } returns id }

    private fun document(code: String = "RAMCOVA_SMLOUVA_CS", status: DocumentStatus = DocumentStatus.GENERATED) =
        Document(
            id = UUID.randomUUID(),
            templateCode = code,
            templateVersion = "1.1.0",
            sha256 = "abc",
            storageKey = "documents/1",
            contentType = "application/pdf",
            sizeBytes = 10,
            status = status,
            metadata = emptyMap(),
            partyRef = partyRef,
            caseRef = accountId.toString(),
            productRef = productId.toString(),
            retainUntil = null,
            createdAt = Instant.parse("2026-01-15T10:15:30Z"),
            idempotencyKey = idempotencyKey,
        )
}
