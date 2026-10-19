// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.payment

import com.openbank.treasury.application.port.out.PaymentRailPort
import com.openbank.treasury.application.port.out.RailHandOff
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.reference.SettlementRail
import jakarta.enterprise.context.ApplicationScoped

/**
 * ADR-0344 D6: which rail can carry a bank-OWN (non-customer) debit today. None can, so every
 * released payment answers [RailHandOff.NotSupported] with the reason, rather than being pushed
 * through a customer-payment endpoint it does not belong in:
 *
 * - CERTIS (domestic-payment): every payment debits a customer account (`debtorAccountId`) under
 *   the initiator's account scope; there is no own-nostro originator.
 * - SEPA (sepa-payment): the same — `debtorAccountId` is required.
 * - SWIFT (swift-service): the model has the bank-to-bank MT202, but only MT103 is ever submitted to
 *   the scheme (an MT202 stops at VALIDATED) and its policy has no machine-to-machine grant.
 * - TARGET2: no rail service exists.
 *
 * When a rail grows a bank-own path, its adapter replaces the branch below (with a Pact consumer
 * test against the provider's @PactFolder replay); the domain and the release flow do not change.
 */
@ApplicationScoped
class OwnAccountRailAdapter : PaymentRailPort {
    override suspend fun handOff(payment: Payment): RailHandOff = RailHandOff.NotSupported(
        when (checkNotNull(payment.ssi) { "an outgoing payment is addressed from an SSI" }.rail) {
            SettlementRail.CERTIS ->
                "RAIL_NOT_SUPPORTED: domestic-payment accepts customer-account debits only; " +
                    "instruct this own-account payment manually"
            SettlementRail.SEPA ->
                "RAIL_NOT_SUPPORTED: sepa-payment accepts customer-account debits only; " +
                    "instruct this own-account payment manually"
            SettlementRail.SWIFT ->
                "RAIL_NOT_SUPPORTED: swift-service does not submit MT202 to the scheme and grants " +
                    "no machine caller; instruct this own-account payment manually"
            SettlementRail.TARGET2 -> "RAIL_NOT_SUPPORTED: no TARGET2 rail is connected; instruct this payment manually"
        },
    )
}
