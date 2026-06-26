// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@ApplicationScoped
class SettlementAdapter(
    @RestClient private val client: TransactionServiceClient,
    private val oidcClient: Instance<OidcClient>,
    private val clock: Clock,
) : SettlementPort {

    @Inject
    lateinit var self: SettlementAdapter

    private val log = Logger.getLogger(SettlementAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(payment: SepaPayment): SettlementOutcome {
        try {
            return self.settleWithResilience(payment)
        } catch (ex: Exception) {
            throw SettlementUnavailableException("transaction-service unavailable for payment ${payment.id}", ex)
        }
    }

    @Suppress("MagicNumber")
    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(8_000)
    open suspend fun settleWithResilience(payment: SepaPayment): SettlementOutcome {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val valueDate = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)

        val request = InitiateSettlementRequest(
            idempotencyKey = "sepa-settlement-${payment.id}",
            type = "TRANSFER",
            sourceAccountId = payment.debtorAccountId,
            amount = payment.amount,
            currencyCode = payment.currency,
            description = "SEPA ${payment.type.name}: ${payment.remittanceInfo ?: payment.endToEndId}",
            valueDate = valueDate,
            rail = "SEPA_CT",
            instructionType = payment.type.name,
        )

        val response = client.initiateTransaction("Bearer $token", request).awaitSuspending()
        return when (response.status) {
            Response.Status.CREATED.statusCode, Response.Status.OK.statusCode -> {
                val body = response.readEntity(Map::class.java)
                val txId = (body["id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                log.infof("Settlement booked for SEPA payment %s → transaction %s", payment.id, txId)
                SettlementOutcome(settled = true, transactionId = txId)
            }
            Response.Status.CONFLICT.statusCode -> {
                log.infof("Settlement already exists for SEPA payment %s (idempotent)", payment.id)
                SettlementOutcome(settled = true, transactionId = null)
            }
            else -> {
                log.errorf("transaction-service returned %d for SEPA payment %s", response.status, payment.id)
                throw SettlementUnavailableException("Unexpected status ${response.status} from transaction-service")
            }
        }
    }
}
