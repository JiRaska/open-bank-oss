// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.BusinessAgreement
import com.openbank.document.application.port.`in`.BusinessAgreementConflictException
import com.openbank.document.application.port.`in`.BusinessAgreementSigner
import com.openbank.document.application.port.`in`.BusinessAgreementUseCase
import com.openbank.document.application.port.`in`.BusinessDisclosure
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.EnsureBusinessAgreementCommand
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.application.port.out.ProductFee
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import jakarta.enterprise.context.ApplicationScoped
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * A company's onboarding agreement: renders the framework agreement (RAMCOVA_SMLOUVA_PO_*) and its
 * annexed disclosures for one kyb case, and opens ONE multi-signer ceremony over it through the
 * same [SignatureCeremonyUseCase] retail signing uses — so every signature is SCA-verified against
 * the agreement's sha256 and the PAdES seal is applied on completion, with no second code path.
 *
 * Idempotency and replacement, per (caseId, lang):
 *  - the agreement Document carries idempotency key `business-agreement:<caseId>:<LANG>` (V6's
 *    partial unique index makes the database the arbiter of a concurrent double call) and the
 *    digest of the request that produced it in its metadata;
 *  - the same request again returns the same agreement, whatever its state;
 *  - a CHANGED request replaces the agreement only while nobody has signed it: the ceremony is
 *    withdrawn (EXPIRED), the document archived (which releases the key) and a new one rendered;
 *  - once any signer has signed — or the agreement is SIGNED — a changed request is a
 *    [BusinessAgreementConflictException]. A contract someone signed is never silently replaced.
 *  - requesting the other language supersedes an unsigned agreement in the first one, exactly as
 *    the retail agreement does (language is changeable until someone signs).
 *
 * Disclosures (general terms, fee schedule, pre-contractual information, deposit insurance) are
 * rendered once per case and code under `business-disclosure:<caseId>:<CODE>` and then reused, so
 * the set a customer accepts is stable across calls even if a newer template version is published
 * meanwhile. All documents carry `partyRef = entityPartyId` and `caseRef = caseId`.
 */
// TooManyFunctions: one use case (ensure/find) whose rules — replacement, disclosures, fee rows,
// validation, digest — are small private steps; splitting them into classes would scatter one
// lifecycle across files for no reuse.
@Suppress("TooManyFunctions")
@ApplicationScoped
class BusinessAgreementService(
    private val renderUseCase: DocumentRenderUseCase,
    private val ceremonyUseCase: SignatureCeremonyUseCase,
    private val documentRepo: DocumentRepositoryPort,
    private val ceremonyRepo: CeremonyRepositoryPort,
    private val templateRepo: TemplateRepositoryPort,
    private val productCatalogPort: ProductCatalogPort,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : BusinessAgreementUseCase {

    override suspend fun ensure(cmd: EnsureBusinessAgreementCommand): BusinessAgreement {
        val locale = validate(cmd)
        val inputHash = inputDigest(cmd)
        supersedeOtherLanguages(cmd.caseId, locale)
        val disclosures = ensureDisclosures(cmd, locale)

        val key = agreementKey(cmd.caseId, locale)
        val existing = documentRepo.findByIdempotencyKey(key)
        if (existing != null) {
            val ceremony = ceremonyUseCase.findByDocumentId(existing.id)
            val ceremonyLive = ceremony == null || ceremony.status !in WITHDRAWN
            if (existing.metadata[INPUT_DIGEST] == inputHash && ceremonyLive) {
                return view(cmd.caseId, existing, ceremony ?: openCeremony(existing.id, cmd), disclosures)
            }
            withdraw(existing, ceremony)
        }

        val document = renderAgreement(cmd, locale, key, inputHash, disclosures)
        val ceremony = ceremonyUseCase.findByDocumentId(document.id) ?: openCeremony(document.id, cmd)
        return view(cmd.caseId, document, ceremony, disclosures)
    }

    override suspend fun find(caseId: UUID, lang: String?): BusinessAgreement? {
        val locales = if (lang.isNullOrBlank()) SUPPORTED_LOCALES else listOf(localeOf(lang))
        val candidates = locales.mapNotNull { l ->
            documentRepo.findByIdempotencyKey(agreementKey(caseId, l))?.let {
                l to
                    it
            }
        }
        val (locale, document) = candidates.firstOrNull { it.second.status == DocumentStatus.SIGNED }
            ?: candidates.firstOrNull()
            ?: return null
        val ceremony = ceremonyUseCase.findByDocumentId(document.id) ?: return null
        val disclosures = DISCLOSURE_BASES.mapNotNull { base ->
            documentRepo.findByIdempotencyKey(disclosureKey(caseId, "${base}_$locale"))?.asDisclosure()
        }
        return view(caseId, document, ceremony, disclosures)
    }

    // ── replacement ─────────────────────────────────────────────────────────────────────────────

    private suspend fun supersedeOtherLanguages(caseId: UUID, locale: String) {
        SUPPORTED_LOCALES.filter { it != locale }.forEach { other ->
            val document = documentRepo.findByIdempotencyKey(agreementKey(caseId, other)) ?: return@forEach
            withdraw(document, ceremonyUseCase.findByDocumentId(document.id))
        }
    }

    /** Withdraws an agreement nobody has signed; refuses (409) once anyone has. */
    private suspend fun withdraw(document: Document, ceremony: SignatureCeremony?) {
        val signedByAnyone = document.status == DocumentStatus.SIGNED ||
            (ceremony != null && ceremony.status !in UNSIGNED)
        if (signedByAnyone) {
            throw BusinessAgreementConflictException(
                "The agreement for this case is already signed (ceremony ${ceremony?.status ?: "COMPLETED"}) " +
                    "and cannot be replaced",
            )
        }
        if (ceremony != null && ceremony.status in OPEN) ceremonyRepo.save(ceremony.expire())
        documentRepo.save(document.archive())
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────

    private suspend fun ensureDisclosures(
        cmd: EnsureBusinessAgreementCommand,
        locale: String,
    ): List<BusinessDisclosure> = DISCLOSURE_BASES.map { base ->
        val code = "${base}_$locale"
        val key = disclosureKey(cmd.caseId, code)
        val document = documentRepo.findByIdempotencyKey(key) ?: renderDisclosure(cmd, locale, code, key)
        document.asDisclosure()
    }

    private suspend fun renderDisclosure(
        cmd: EnsureBusinessAgreementCommand,
        locale: String,
        code: String,
        key: String,
    ): Document {
        val template = templateRepo.findLatestPublished(code) ?: error("No published template for $code")
        val data = commonData(cmd, locale) +
            if (code.startsWith(FEE_SCHEDULE_BASE)) feeScheduleData(cmd, locale) else emptyMap()
        return renderIdempotently(
            RenderDocumentCommand(
                templateCode = code,
                templateVersion = template.version,
                data = data,
                contentType = PDF,
                partyRef = cmd.entityPartyId.toString(),
                caseRef = cmd.caseId.toString(),
                productRef = cmd.product.code,
                retainUntil = null,
                idempotencyKey = key,
                metadata = mapOf(TITLE to template.name),
            ),
        )
    }

    private suspend fun renderAgreement(
        cmd: EnsureBusinessAgreementCommand,
        locale: String,
        key: String,
        inputHash: String,
        disclosures: List<BusinessDisclosure>,
    ): Document {
        val data = commonData(cmd, locale) + mapOf(
            "entity" to mapOf(
                "name" to cmd.entity.name,
                "ico" to cmd.entity.ico,
                "seat" to cmd.entity.seat,
                "legalForm" to cmd.entity.legalForm,
            ),
            "representatives" to cmd.representatives.map { mapOf("name" to it.name, "role" to it.role) },
            "signers" to cmd.signers.map { mapOf("name" to it.name, "role" to it.role) },
            "signingRule" to cmd.signingRule,
            "agreement" to mapOf("date" to today(), "number" to agreementNumber(cmd.caseId)),
            "disclosures" to disclosures.map { mapOf("title" to it.title, "code" to it.code, "version" to it.version) },
        )
        return renderIdempotently(
            RenderDocumentCommand(
                templateCode = "${AGREEMENT_BASE}_$locale",
                templateVersion = null,
                data = data,
                contentType = PDF,
                partyRef = cmd.entityPartyId.toString(),
                caseRef = cmd.caseId.toString(),
                productRef = cmd.product.code,
                retainUntil = null,
                idempotencyKey = key,
                metadata = mapOf(INPUT_DIGEST to inputHash),
            ),
        )
    }

    // SwallowedException: the duplicate IS the "a concurrent call rendered this" signal.
    @Suppress("SwallowedException")
    private suspend fun renderIdempotently(cmd: RenderDocumentCommand): Document = try {
        renderUseCase.render(cmd)
    } catch (e: DuplicateDocumentException) {
        val key = requireNotNull(cmd.idempotencyKey)
        documentRepo.findByIdempotencyKey(key) ?: error("Duplicate document but nothing under $key")
    }

    @Suppress("SwallowedException")
    private suspend fun openCeremony(documentId: UUID, cmd: EnsureBusinessAgreementCommand): SignatureCeremony = try {
        ceremonyUseCase.openCeremony(
            OpenCeremonyCommand(documentId, cmd.signers.map { it.partyRef }, SignatureLevel.ADVANCED),
        )
    } catch (e: DuplicateCeremonyException) {
        ceremonyUseCase.findByDocumentId(documentId)
            ?: error("Ceremony for document $documentId vanished after DuplicateCeremonyException")
    }

    private fun commonData(cmd: EnsureBusinessAgreementCommand, locale: String): Map<String, Any?> = mapOf(
        "bank" to mapOf("name" to BANK_NAME, "seat" to bankSeat(locale), "ico" to BANK_ICO),
        "party" to mapOf("name" to cmd.entity.name),
        "product" to mapOf("name" to cmd.product.name, "code" to cmd.product.code, "currency" to cmd.product.currency),
        "document" to mapOf("date" to today(), "caseRef" to cmd.caseId.toString()),
    )

    private suspend fun feeScheduleData(cmd: EnsureBusinessAgreementCommand, locale: String): Map<String, Any?> {
        val schedule = productCatalogPort.findFeeSchedule(cmd.product.code)
        check(schedule != null && schedule.fees.isNotEmpty()) {
            "Product catalogue has no fee schedule for product ${cmd.product.code}; the fee schedule annex cannot be issued"
        }
        return mapOf(
            "product" to mapOf(
                "name" to (schedule.name ?: cmd.product.name),
                "code" to schedule.code,
                "currency" to (schedule.currency ?: cmd.product.currency),
            ),
            "fees" to schedule.fees.map { feeRow(it, locale) },
        )
    }

    private fun feeRow(fee: ProductFee, locale: String): Map<String, Any?> {
        val cs = locale == "CS"
        val amount = amountFormat(cs).format(fee.amount)
        return mapOf(
            "name" to fee.name,
            "description" to fee.description,
            "waiveCondition" to fee.waiveCondition,
            "frequency" to ((if (cs) FREQUENCY_CS else FREQUENCY_EN)[fee.frequency] ?: fee.frequency),
            "amount" to when {
                fee.frequency == PERCENTAGE -> "$amount %"
                cs -> "$amount ${fee.currency}"
                else -> "${fee.currency} $amount"
            },
        )
    }

    // ── mapping ─────────────────────────────────────────────────────────────────────────────────

    private fun view(
        caseId: UUID,
        document: Document,
        ceremony: SignatureCeremony,
        disclosures: List<BusinessDisclosure>,
    ) = BusinessAgreement(
        caseId = caseId,
        documentId = document.id,
        templateCode = document.templateCode,
        templateVersion = document.templateVersion,
        sha256 = document.sha256,
        sealedSha256 = document.sealedSha256,
        ceremonyId = ceremony.id,
        ceremonyStatus = ceremony.status,
        signers = ceremony.signers.sortedBy {
            it.order
        }.map { BusinessAgreementSigner(it.partyRef, it.status, it.signedAt) },
        disclosures = disclosures,
    )

    private fun Document.asDisclosure() = BusinessDisclosure(
        code = templateCode,
        version = templateVersion,
        title = metadata[TITLE] ?: templateCode,
        sha256 = sha256,
        documentId = id,
    )

    private fun validate(cmd: EnsureBusinessAgreementCommand): String {
        val locale = localeOf(cmd.lang)
        require(cmd.signers.isNotEmpty()) { "signers must not be empty" }
        require(cmd.signers.map { it.partyRef }.toSet().size == cmd.signers.size) { "signers must be distinct parties" }
        cmd.signers.forEach { s ->
            require(runCatching { UUID.fromString(s.partyRef) }.isSuccess) { "signer partyRef must be a party UUID" }
            require(s.name.isNotBlank() && s.role.isNotBlank()) { "every signer needs a name and a role" }
        }
        require(cmd.representatives.all { it.name.isNotBlank() && it.role.isNotBlank() }) {
            "every representative needs a name and a role"
        }
        with(cmd.entity) {
            require(name.isNotBlank() && ico.isNotBlank() && seat.isNotBlank() && legalForm.isNotBlank()) {
                "entity name, ico, seat and legalForm are required"
            }
        }
        require(cmd.signingRule.isNotBlank()) { "signingRule is required" }
        with(cmd.product) {
            require(code.isNotBlank() && name.isNotBlank() && currency.isNotBlank()) {
                "product code, name and currency are required"
            }
        }
        return locale
    }

    /** Digest over everything that shapes the rendered agreement — a change means a different contract. */
    private fun inputDigest(cmd: EnsureBusinessAgreementCommand): String = Document.sha256(
        objectMapper.writeValueAsBytes(
            listOf(
                cmd.entityPartyId.toString(),
                localeOf(cmd.lang),
                listOf(cmd.entity.name, cmd.entity.ico, cmd.entity.seat, cmd.entity.legalForm),
                cmd.representatives.map { listOf(it.partyRef, it.name, it.role) },
                cmd.signers.map { listOf(it.partyRef, it.name, it.role) },
                cmd.signingRule,
                listOf(cmd.product.code, cmd.product.name, cmd.product.currency),
            ),
        ),
    )

    private fun localeOf(lang: String): String {
        val locale = lang.trim().uppercase()
        require(locale in SUPPORTED_LOCALES) { "lang must be one of cs, en" }
        return locale
    }

    private fun today(): String = LocalDate.now(clock).toString()

    private fun agreementNumber(caseId: UUID) =
        "PO-" + caseId.toString().replace("-", "").take(AGREEMENT_NUMBER_LENGTH).uppercase()

    private fun bankSeat(locale: String) = if (locale == "CS") BANK_SEAT_CS else BANK_SEAT_EN

    private fun amountFormat(cs: Boolean) =
        DecimalFormat("#,##0.##", DecimalFormatSymbols(if (cs) CZECH else Locale.ENGLISH)).apply {
            isParseBigDecimal = true
        }

    private fun agreementKey(caseId: UUID, locale: String) = "business-agreement:$caseId:$locale"

    private fun disclosureKey(caseId: UUID, code: String) = "business-disclosure:$caseId:$code"

    companion object {
        const val AGREEMENT_BASE = "RAMCOVA_SMLOUVA_PO"
        const val FEE_SCHEDULE_BASE = "SAZEBNIK_PO"

        /** Annex order as printed in the agreement. VOP is the bank's existing general terms. */
        val DISCLOSURE_BASES =
            listOf("VOP", FEE_SCHEDULE_BASE, "PREDSMLUVNI_INFORMACE_PO", "INFORMACE_POJISTENI_VKLADU")
        val SUPPORTED_LOCALES = listOf("CS", "EN")

        // Bank identity exactly as the retail templates print it (DocumentTemplateSeed letterhead).
        private const val BANK_NAME = "OpenBank a.s."
        private const val BANK_ICO = "000 00 001"
        private const val BANK_SEAT_CS = "Na Příkopě 1, 110 00 Praha 1"
        private const val BANK_SEAT_EN = "Na Příkopě 1, 110 00 Prague 1"

        private const val PDF = "application/pdf"
        private const val INPUT_DIGEST = "businessAgreementInputSha256"
        private const val TITLE = "title"
        private const val PERCENTAGE = "PERCENTAGE"
        private const val AGREEMENT_NUMBER_LENGTH = 12
        private val CZECH: Locale = Locale.forLanguageTag("cs-CZ")

        private val OPEN = setOf(CeremonyStatus.DRAFT, CeremonyStatus.PENDING)
        private val WITHDRAWN = setOf(CeremonyStatus.DECLINED, CeremonyStatus.EXPIRED)
        private val UNSIGNED = OPEN + WITHDRAWN

        private val FREQUENCY_CS = mapOf(
            "MONTHLY" to "měsíčně",
            "ANNUAL" to "ročně",
            "DAILY" to "denně",
            "ONE_TIME" to "jednorázově",
            "PER_TRANSACTION" to "za transakci",
            "PER_OCCURRENCE" to "za každý případ",
            PERCENTAGE to "z částky transakce",
        )
        private val FREQUENCY_EN = mapOf(
            "MONTHLY" to "monthly",
            "ANNUAL" to "annually",
            "DAILY" to "daily",
            "ONE_TIME" to "one-off",
            "PER_TRANSACTION" to "per transaction",
            "PER_OCCURRENCE" to "per occurrence",
            PERCENTAGE to "of the transaction amount",
        )
    }
}
