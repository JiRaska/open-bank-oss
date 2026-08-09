// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.AnnualFeeSummaryReadyCommand
import com.openbank.document.application.port.`in`.AnnualStatementDeliveryUseCase
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.out.AccountInfo
import com.openbank.document.application.port.out.AccountLookupPort
import com.openbank.document.application.port.out.PartyInfo
import com.openbank.document.application.port.out.PartyLookupPort
import com.openbank.document.application.port.out.StatementDeliveryPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.libs.idempotency.IdempotencyStore
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

/**
 * ADR-0248: the annual statement of fees is the one template family that still needs an async
 * Kafka trigger (PAD Art. 5 push duty — the bank must send it, not wait for the customer). Renders
 * via [DocumentTemplateUseCase.previewRender] — the same NON-PERSISTING path an editor uses to
 * preview a draft — never [DocumentRenderUseCase][com.openbank.document.application.port.in.DocumentRenderUseCase],
 * which would persist a `Document` row and emit `document.generated`. The bytes are handed to
 * [StatementDeliveryPort] and never touch the object store.
 *
 * **Idempotent under at-least-once Kafka redelivery.** Unlike [OnboardingDocumentService], there is
 * no persisted `Document` row to key a replay guard off — this use case never writes one — so it
 * reuses the fleet's [IdempotencyStore] (Redis-backed) directly, keyed on `(accountId, year)`: a
 * customer has at most one annual statement per year, so a redelivered event for the same pair is
 * a pure replay, not a legitimate re-delivery for a *different* fee summary. `24h` TTL would be too
 * short for a once-a-year event that might legitimately retry after an outage, so this uses a
 * longer TTL — see [IDEMPOTENCY_TTL_SECONDS].
 *
 * **Locale.** The `AnnualFeeSummaryReady` contract (ADR-0248, authoritative, shared with
 * `billing-service`'s producer) carries no language field, so this always renders the Czech
 * variant (`ROCNI_VYPIS_POPLATKU_CS`) — the fleet's existing default locale convention
 * ([OnboardingDocumentService.DEFAULT_LOCALE]). Picking a locale from `currency` would be a false
 * signal (a CZK account can belong to a party who reads English) rather than a real one; adding a
 * language field to the event contract is a follow-up, not something this consumer can invent
 * unilaterally without also changing the producer side.
 */
@ApplicationScoped
class AnnualStatementDeliveryService(
    private val templateRepo: TemplateRepositoryPort,
    private val templateUseCase: DocumentTemplateUseCase,
    private val partyLookupPort: PartyLookupPort,
    private val accountLookupPort: AccountLookupPort,
    private val deliveryPort: StatementDeliveryPort,
    private val idempotencyStore: IdempotencyStore,
) : AnnualStatementDeliveryUseCase {

    private val log = Logger.getLogger(AnnualStatementDeliveryService::class.java)

    override suspend fun deliverAnnualStatement(cmd: AnnualFeeSummaryReadyCommand) {
        val idempotencyKey = idempotencyKey(cmd.accountId, cmd.year)
        if (idempotencyStore.get(idempotencyKey) != null) {
            log.infof(
                "Annual statement already delivered for account %s year %d — skipping (idempotent replay).",
                cmd.accountId,
                cmd.year,
            )
            return
        }

        val template = templateRepo.findLatestPublished(TEMPLATE_CODE)
        if (template == null) {
            log.warnf(
                "No PUBLISHED %s template — cannot deliver the annual statement for account %s year %d.",
                TEMPLATE_CODE,
                cmd.accountId,
                cmd.year,
            )
            return
        }

        val partyId = runCatching { UUID.fromString(cmd.partyRef) }.getOrNull()
        val party = partyId?.let { partyLookupPort.findById(it) }
        val account = partyId?.let { accountLookupPort.findCurrentAccount(it) }

        val renderedHtml = templateUseCase.previewRender(template.bodyHtml, buildRenderData(cmd, party, account))
        deliveryPort.deliver(
            partyRef = cmd.partyRef,
            documentBytes = renderedHtml.toByteArray(Charsets.UTF_8),
            contentType = "text/html",
            subject = "Annual statement of fees ${cmd.year} — account ${cmd.accountId}",
        )

        idempotencyStore.save(
            idempotencyKey,
            statusCode = DELIVERED_STATUS,
            responseBody = "delivered",
            ttlSeconds = IDEMPOTENCY_TTL_SECONDS,
        )
        log.infof("Delivered the annual statement of fees for account %s year %d.", cmd.accountId, cmd.year)
    }

    /**
     * Namespacing mirrors [OnboardingDocumentService.buildAgreementData]: `document.*`/`party.*`/
     * `account.*`, the convention every seeded template's Handlebars placeholders already assume.
     * [AnnualFeeLine.name]/[AnnualFeeLine.category]/[AnnualFeeLine.amount] map straight through —
     * the event's `code` field has no placeholder in the template and is dropped here on purpose.
     */
    private fun buildRenderData(
        cmd: AnnualFeeSummaryReadyCommand,
        party: PartyInfo?,
        account: AccountInfo?,
    ): Map<String, Any?> = mapOf(
        "document" to mapOf(
            "year" to cmd.year,
            "fees" to cmd.fees.map {
                mapOf("name" to it.name, "category" to it.category, "amount" to it.amount.toPlainString())
            },
            "totalFees" to cmd.totalFees.toPlainString(),
            "interestRate" to cmd.interestRate?.toPlainString(),
        ),
        "party" to mapOf("name" to (party?.legalName ?: ""), "address" to party?.formattedAddress),
        "account" to mapOf("iban" to account?.iban),
    )

    private fun idempotencyKey(accountId: UUID, year: Int) = "$IDEMPOTENCY_KEY_PREFIX$accountId:$year"

    private companion object {
        const val TEMPLATE_CODE = "ROCNI_VYPIS_POPLATKU_CS"
        const val IDEMPOTENCY_KEY_PREFIX = "annual-statement:"
        const val DELIVERED_STATUS = 200

        // A year-cadence event can legitimately be redelivered long after the first attempt (a
        // multi-day outage in the delivery chain, a manual outbox re-drain) — 24h (the
        // IdempotencyStore default) would let a redelivery slip through and double-send. 400 days
        // comfortably outlives any redelivery window while still expiring before next year's event
        // for the SAME account would otherwise collide with a stale key (it never can: the key
        // carries the year, so no collision is possible — this TTL only bounds Redis memory).
        const val IDEMPOTENCY_TTL_SECONDS = 400L * 24 * 60 * 60
    }
}
