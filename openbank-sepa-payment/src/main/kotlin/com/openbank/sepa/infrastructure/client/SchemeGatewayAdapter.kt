// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Reader
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepa.domain.model.SepaPayment
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Submits a real ISO 20022 `pacs.008` to the scheme gateway and maps the `pacs.002` response to a
 * [SchemeSubmissionOutcome] (ADR-0104 D3). Builds the message with the shared `openbank-libs`
 * builder so the wire format is identical to what a real gateway would receive, validates it
 * against the XSD before it leaves the rail, and reads the verdict with the shared reader.
 *
 * Fails **closed** (ADR-0032 posture): if the gateway is unreachable it throws
 * [SchemeGatewayUnavailableException] so the caller holds the payment rather than releasing it.
 */
@ApplicationScoped
class SchemeGatewayAdapter(
    @RestClient private val client: ClearingSimulatorClient,
    @ConfigProperty(name = "openbank.bank.bic") private val ownBankBic: String,
    // Lazy Instance: the default OidcClient bean is absent when oidc-client is disabled (the %test
    // profile), so a direct injection would fail Arc validation for every @QuarkusTest that boots
    // this bean. Resolved on demand, only on the scheme-submission path (oidc-client enabled in prod).
    private val oidcClient: Instance<OidcClient>,
) : SchemeGatewayPort {

    @Inject
    lateinit var self: SchemeGatewayAdapter

    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val statusReader = Pacs002Reader()
    private val log = Logger.getLogger(SchemeGatewayAdapter::class.java)

    // Fail-closed: any gateway error must be caught and surfaced as "unavailable" so the caller holds
    // the payment rather than releasing it (ADR-0032 posture; mirrors SanctionsScreeningAdapter).
    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(payment: SepaPayment): SchemeSubmissionOutcome {
        // The creditor agent BIC is required to route the transfer; absent it the scheme would
        // reject (RC01 — bank identifier incorrect), so we surface that without a round-trip.
        val creditorAgentBic = payment.creditorBic
            ?: return SchemeSubmissionOutcome(accepted = false, reasonCode = "RC01")

        val pacs008 = builder.build(instruction(payment, creditorAgentBic))
        check(validator.validate(pacs008) is Iso20022ValidationResult.Valid) {
            "rail built a non-conforming pacs.008 for payment ${payment.id}"
        }

        // Both the transport AND the response parse are inside the fail-closed boundary: an
        // unreachable gateway or an unparseable/garbled pacs.002 both surface as "unavailable"
        // so the caller holds the payment, rather than escaping as an unhandled 500.
        return try {
            val pacs002 = self.submitWithResilience(pacs008)
            val status = statusReader.read(pacs002)
            SchemeSubmissionOutcome(
                accepted = status.status == PaymentStatus.ACSC,
                reasonCode = status.reasonCode,
            )
        } catch (ex: Exception) {
            log.warnf(ex, "Scheme gateway unavailable for payment %s; holding", payment.id)
            throw SchemeGatewayUnavailableException(ex)
        }
    }

    // Resilience tuning constants (mirrors the established SanctionsScreeningAdapter values).
    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    open suspend fun submitWithResilience(pacs008Xml: String): String {
        // Acquire the service token explicitly and pass it as the Authorization header. The
        // OidcClientRequestReactiveFilter does not attach a token when this call runs on a
        // Temporal-activity Vert.x duplicated context (production path) → 401 (ADR-0104 BUG #3).
        // OidcClient caches and refreshes the token, so this stays cheap across retries.
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        return client.submitCreditTransfer("Bearer $token", pacs008Xml).awaitSuspending()
    }

    private fun instruction(payment: SepaPayment, creditorAgentBic: String) = CreditTransferInstruction(
        messageId = "MSG-${payment.endToEndId}".take(MAX_35),
        creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        endToEndId = payment.endToEndId,
        transactionId = null,
        amount = payment.amount,
        currency = payment.currency,
        // SEPA SCT rulebook: charges shared at service level, settled via the clearing system.
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = payment.debtorName,
        debtorIban = payment.debtorIban,
        debtorAgentBic = ownBankBic,
        creditorAgentBic = creditorAgentBic,
        creditorName = payment.creditorName,
        creditorIban = payment.creditorIban,
        remittanceInfo = payment.remittanceInfo,
    )

    private companion object {
        const val MAX_35 = 35
    }
}
