// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * The real read adapter behind [PaymentConfirmationReadPort] (issue #4109, ADR-0248).
 *
 * Every other adapter on this surface ([RealAccountReadPort]) validates the presented consent
 * BEFORE touching downstream data, because the caller-given argument (an IBAN) already names the
 * account to intersect against `grantedAccounts`. A payment confirmation cannot follow that order:
 * [paymentId] names neither a rail nor an account, so which account (and therefore whether the
 * consent covers it) is only known once the payment itself has been fetched. This adapter still
 * fails closed — it validates the SCOPE up front (so a caller without `PAYMENTS_STATUS_READ` never
 * reaches a payment service at all) and validates the account intersection again once the debtor
 * account is known, before returning anything. No branch returns payment data without both checks
 * having passed.
 */
@ApplicationScoped
class RealPaymentConfirmationReadPort(
    @RestClient private val consent: ConsentValidateClient,
    @RestClient private val accounts: AccountServiceClient,
    @RestClient private val sepaPayments: SepaPaymentServiceClient,
    @RestClient private val domesticPayments: DomesticPaymentServiceClient,
) : PaymentConfirmationReadPort {

    private val gate = ConsentGate(consent)

    override fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String): JsonNode {
        val id = runCatching { UUID.fromString(paymentId) }.getOrElse {
            throw IllegalArgumentException("paymentId '$paymentId' is not a valid payment id")
        }
        // Scope-only check first (accountIban = null, mirrors listConsents/listAccounts): fails
        // closed before either payment service is ever called.
        gate.validate(consentContext, ConsentScopes.PAYMENTS_STATUS_READ, accountIban = null)

        val (payment, rail) = fetchPayment(id)
        val debtorIban = resolveDebtorIban(payment, rail)
        // The account-intersection check the caller-given argument would normally drive up front —
        // done here instead, now that the debtor account is known (see class KDoc).
        gate.validate(consentContext, ConsentScopes.PAYMENTS_STATUS_READ, accountIban = debtorIban)

        return payment.deepCopy<ObjectNode>().put("rail", rail)
    }

    private fun fetchPayment(id: UUID): Pair<JsonNode, String> {
        try {
            return sepaPayments.getPayment(id) to "SEPA"
        } catch (ignored: NotFoundException) {
            // Fall through to the domestic rail — see class KDoc on why a paymentId names no rail.
        }
        try {
            return domesticPayments.getPayment(id) to "DOMESTIC"
        } catch (ignored: NotFoundException) {
            throw NoSuchElementException("no SEPA or domestic payment found for id '$id'")
        }
    }

    /**
     * SEPA payments carry `debtorIban` directly; domestic payments carry only the internal
     * `debtorAccountId` (ČOBS domestic instructions use account-number + bank-code, not IBAN), so
     * that rail needs one extra hop through account-service to recover the IBAN
     * `grantedAccounts`/consent-service actually grant against.
     */
    private fun resolveDebtorIban(payment: JsonNode, rail: String): String = when (rail) {
        "SEPA" -> payment.path("debtorIban").asText()
        else -> {
            val debtorAccountId = UUID.fromString(payment.path("debtorAccountId").asText())
            accounts.getAccountById(debtorAccountId).path("accountNumber").asText()
        }
    }
}
