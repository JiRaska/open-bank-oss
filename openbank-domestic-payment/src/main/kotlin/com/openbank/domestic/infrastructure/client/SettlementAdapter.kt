// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Adapter over [TransactionServiceClient] — books the debit leg in transaction-service once
 * the Czech CERTIS scheme returns ACSC (ADR-0108). The idempotency key is payment-scoped so
 * Temporal retries never double-book. HTTP 409 (duplicate) is treated as a successful
 * already-booked outcome.
 *
 * The OIDC token is acquired explicitly (not via OidcClientRequestReactiveFilter) because the
 * filter loses the Vert.x context on Temporal activity threads — same root cause as ADR-0104
 * BUG #3 for the SEPA scheme gateway.
 */
@ApplicationScoped
class SettlementAdapter(
    @RestClient private val client: TransactionServiceClient,
    // Lazy Instance: absent when oidc-client is disabled under %test so a direct injection
    // would fail Arc validation for every @QuarkusTest. Resolved on demand in prod only.
    private val oidcClient: Instance<OidcClient>,
    private val clock: Clock,
) : SettlementPort {

    @Inject
    lateinit var self: SettlementAdapter

    private val log = Logger.getLogger(SettlementAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(payment: DomesticPayment): SettlementOutcome = try {
        self.settleWithResilience(payment)
    } catch (ex: SettlementUnavailableException) {
        throw ex
    } catch (ex: Exception) {
        throw SettlementUnavailableException(
            "transaction-service unavailable for payment ${payment.id}",
            ex,
        )
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(SETTLE_TIMEOUT_MS)
    open suspend fun settleWithResilience(payment: DomesticPayment): SettlementOutcome {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val valueDate = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
        val description = buildDescription(payment)

        val response = client.initiateTransaction(
            "Bearer $token",
            InitiateSettlementRequest(
                idempotencyKey = "domestic-settlement-${payment.id}",
                type = "DEBIT",
                sourceAccountId = payment.debtorAccountId,
                amount = payment.amount,
                currencyCode = payment.currency,
                description = description,
                valueDate = valueDate,
                rail = "DOMESTIC",
                instructionType = "ONE_OFF",
            ),
        ).awaitSuspending()

        return when (response.status) {
            HTTP_CREATED -> {
                val txId = extractTransactionId(response)
                log.infof("Settlement booked for payment %s → transactionId=%s", payment.id, txId)
                SettlementOutcome(settled = true, transactionId = txId)
            }
            HTTP_CONFLICT -> {
                log.infof(
                    "Settlement already booked (409) for payment %s — idempotent success",
                    payment.id,
                )
                SettlementOutcome(settled = true, transactionId = null)
            }
            else -> {
                throw SettlementUnavailableException(
                    "transaction-service returned HTTP ${response.status} for payment ${payment.id}",
                )
            }
        }
    }

    private fun buildDescription(payment: DomesticPayment): String {
        val info = payment.messageForPayee
            ?: listOfNotNull(
                payment.variableSymbol?.let { "VS:$it" },
                payment.specificSymbol?.let { "SS:$it" },
                payment.constantSymbol?.let { "KS:$it" },
            ).joinToString(" ").ifBlank { payment.endToEndId }
            ?: payment.id.toString()
        return "CERTIS $info".take(MAX_DESCRIPTION)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun extractTransactionId(response: jakarta.ws.rs.core.Response): UUID? = try {
        val body = response.readEntity(String::class.java) ?: return null
        // Minimal JSON extraction — the response body is {"id":"<uuid>",...}.
        val match = UUID_PATTERN.find(body)
        match?.groupValues?.get(1)?.let { UUID.fromString(it) }
    } catch (ex: Exception) {
        log.warnf(ex, "Could not parse transactionId from transaction-service response")
        null
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val MAX_DESCRIPTION = 140
        const val SETTLE_TIMEOUT_MS = 8_000L
        val UUID_PATTERN =
            Regex(""""id"\s*:\s*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"""")
    }
}
