// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.AccrualPostingReceipt
import com.openbank.lending.application.port.out.LedgerPostResult
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.PreparedAccrualPosting
import com.openbank.lending.infrastructure.client.LedgerCallGuard
import com.openbank.lending.infrastructure.client.LendingGlChart
import com.openbank.lending.infrastructure.client.LendingJournalFactory
import com.openbank.lending.infrastructure.client.LendingLedgerConfig
import com.openbank.lending.infrastructure.client.PostJournalRequest
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Real [LedgerPostingPort]: posts the loan book's cash events to ledger-service as balanced
 * double-entry journals (ADR-0028 D3), using the same `POST /api/v1/journals` contract the
 * transaction-service uses — the platform's only ledger ingestion surface.
 *
 * Build-time gated by `lending.ledger.backend=rest`; when unset the `@Default` no-op stays bound and
 * the service builds and boots with zero external dependency (the platform realization pattern,
 * ADR-0045), exactly like the analytics adapters.
 */
// `@Unremovable` because a test asserts this bean's PRESENCE (LedgerAdapterBindingIT, #6057).
// The test-scope `@Priority(200)` stubs outrank it, which makes it unused, and ArC would then
// remove it for a reason unrelated to the build-time gate under test — the assertion would fail
// against correct code. No effect in production, where nothing outranks it. `@IfBuildProperty`
// still disables it outright when the backend is not selected, so the negative case is unaffected.
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "lending.ledger.backend", stringValue = "rest")
class RestLedgerPostingAdapter(
    private val guard: LedgerCallGuard,
    private val config: LendingLedgerConfig,
    private val clock: Clock,
) : LedgerPostingPort {

    private val log = Logger.getLogger(RestLedgerPostingAdapter::class.java)

    @Inject
    lateinit var objectMapper: ObjectMapper

    override fun prepareAccrual(posting: LedgerPosting): PreparedAccrualPosting {
        require(posting.kind == com.openbank.lending.application.port.out.PostingKind.INTEREST_ACCRUAL)
        requireNotNull(posting.accountingDate) { "Accrual posting requires an explicit accounting date" }
        val request = buildRequest(posting)
        return PreparedAccrualPosting(
            posting.reference,
            request.transactionId,
            LocalDate.parse(request.entryDate),
            objectMapper.writeValueAsString(request),
        )
    }

    override fun postPreparedAccrual(posting: PreparedAccrualPosting): Uni<AccrualPostingReceipt> {
        val request = objectMapper.readValue(posting.requestJson, PostJournalRequest::class.java)
        require(objectMapper.writeValueAsString(request) == posting.requestJson) {
            "Persisted accrual request schema changed; reconciliation is required"
        }
        require(request.idempotencyKey == posting.reference && request.transactionId == posting.transactionId)
        require(request.transactionId == UUID.nameUUIDFromBytes(posting.reference.toByteArray(Charsets.UTF_8)))
        require(LocalDate.parse(request.entryDate) == posting.accountingDate)
        return guard.postJournal(request).map { response ->
            require(response.status == "POSTED" && response.id != UUID(0L, 0L)) {
                "Ledger did not confirm a posted journal"
            }
            require(response.transactionId == posting.transactionId) {
                "Ledger transaction does not match accrual intent"
            }
            val actualDate = LocalDate.parse(requireNotNull(response.entryDate) { "Ledger receipt has no entry date" })
            require(actualDate == posting.accountingDate) {
                "Ledger entry date does not match persisted accrual intent"
            }
            AccrualPostingReceipt(response.id, response.transactionId, actualDate)
        }
    }

    override fun post(posting: LedgerPosting): Uni<Unit> {
        val request = buildRequest(posting)
        return guard.postJournal(request)
            .invoke { response ->
                log.debugf("ledger journal %s posted (%s) for %s", response.id, response.status, posting.reference)
            }
            .map { Unit }
    }

    override fun postReportingReplay(posting: LedgerPosting): Uni<LedgerPostResult> =
        guard.postJournalWithHeaders(buildRequest(posting)).map { response ->
            when (response.getHeaderString(IDEMPOTENT_REPLAYED_HEADER)?.lowercase()) {
                "true" -> LedgerPostResult.REPLAYED
                "false" -> LedgerPostResult.POSTED
                else -> LedgerPostResult.UNCONFIRMED
            }
        }

    private fun buildRequest(posting: LedgerPosting): PostJournalRequest = LendingJournalFactory.buildRequest(
        posting = posting,
        // Select the GL account set matching the loan's own currency — ledger-service 422s a line
        // whose currency doesn't match its GL account's currency (issue #1275). Fails loud on an
        // unseeded currency rather than mis-posting.
        accounts = LendingGlChart.accountsFor(posting.amount.currency.code),
        systemActorId = config.systemActorId(),
        date = posting.accountingDate ?: LocalDate.now(clock),
    )

    private companion object {
        /** Set by ledger-service on `POST /api/v1/journals` since API 1.20.0 (#10904). */
        const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"
    }
}
