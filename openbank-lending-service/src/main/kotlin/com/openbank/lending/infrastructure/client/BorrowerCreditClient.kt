// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.libs.domain.money.Money
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Credits or debits the borrower's own account against transaction-service — the customer-facing
 * half of a disbursement / cooling-off unwind that the loan book's own ledger journal
 * ([com.openbank.lending.infrastructure.client.LendingJournalFactory]) never touches, because that
 * journal's two legs (Loans Receivable, Funding Clearing) are both internal GL accounts.
 *
 * `rail` is deliberately omitted: the credit/debit carries `targetAccountId`/`sourceAccountId`
 * pointing at the borrower's own account, which is exactly the signal
 * `com.openbank.libs.domain.payment.SettlementScope` reads as "stays in the bank" — the same-day
 * booking a loan disbursement needs, with no scheme or clearing calendar involved.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceRestClient {
    @POST
    fun initiate(request: InitiateTransactionBody): Uni<TransactionAck>
}

data class InitiateTransactionBody(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID? = null,
    val targetAccountId: UUID? = null,
    val amount: java.math.BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
)

data class TransactionAck(val id: UUID, val status: String)

// Same alternative priority as RestLedgerPostingAdapter (this module).
private const val REST_ADAPTER_PRIORITY = 100

// `@Unremovable` because a test asserts this bean's PRESENCE (LedgerAdapterBindingIT, #6057).
// The test-scope `@Priority(200)` stubs outrank it, which makes it unused, and ArC would then
// remove it for a reason unrelated to the build-time gate under test — the assertion would fail
// against correct code. No effect in production, where nothing outranks it. `@IfBuildProperty`
// still disables it outright when the backend is not selected, so the negative case is unaffected.
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(REST_ADAPTER_PRIORITY)
@IfBuildProperty(name = "lending.borrower-credit.backend", stringValue = "rest")
class BorrowerCreditClient(
    @org.eclipse.microprofile.rest.client.inject.RestClient private val client: TransactionServiceRestClient,
    private val clock: Clock,
) : BorrowerCreditPort {

    // idempotencyKey is the loan/disbursement reference, so a retried credit collapses to the one
    // that already landed rather than paying the borrower twice.
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(CALL_TIMEOUT_MS)
    override fun credit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> = post(
        reference,
        type = "CREDIT",
        targetAccountId = borrowerAccountId,
        sourceAccountId = null,
        amount = amount,
        description = "Loan disbursement",
    )

    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(CALL_TIMEOUT_MS)
    override fun debit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> = post(
        reference,
        type = "DEBIT",
        targetAccountId = null,
        sourceAccountId = borrowerAccountId,
        amount = amount,
        description = "Loan disbursement unwind",
    )

    private fun post(
        reference: String,
        type: String,
        sourceAccountId: UUID?,
        targetAccountId: UUID?,
        amount: Money,
        description: String,
    ): Uni<Unit> = client.initiate(
        InitiateTransactionBody(
            idempotencyKey = reference,
            type = type,
            sourceAccountId = sourceAccountId,
            targetAccountId = targetAccountId,
            amount = amount.amount,
            currencyCode = amount.currency.code,
            description = description,
            valueDate = LocalDate.now(clock).toString(),
        ),
    ).map { }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 500L
        const val RETRY_JITTER_MS = 100L
        const val CALL_TIMEOUT_MS = 3000L
    }
}
