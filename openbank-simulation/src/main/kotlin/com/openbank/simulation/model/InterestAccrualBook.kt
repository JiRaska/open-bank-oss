// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal

/**
 * Simulated interest state (ADR-0033, issue #667): per `(account, currency)`, every amount the
 * interest path produced — daily accruals, the gross/net/tax split decided at capitalization by
 * the real `WithholdingTaxPolicy`, and the journal legs actually posted to the ledger — so
 * `MoneyPathInvariants.interestCapitalizationConservation` can assert the ADR-0033 conservation
 * laws directly: nothing capitalizes that never accrued, the gross splits *exactly* into
 * net + tax (statutory whole-CZK rounding must never create or destroy money), and everything
 * capitalized lands in the ledger (net to the customer, tax to the withholding-payable GL).
 *
 * The same both-sides bookkeeping shape as [BillingFeeLedger] (ADR-0143 phase 2d): the scenario
 * records what the domain computed AND what the ledger received, and the invariant reconciles
 * the two independently-written sides.
 */
class InterestAccrualBook {

    private val accrued = mutableMapOf<AccountCurrency, BigDecimal>()
    private val capitalizedGross = mutableMapOf<AccountCurrency, BigDecimal>()
    private val capitalizedNet = mutableMapOf<AccountCurrency, BigDecimal>()
    private val taxWithheld = mutableMapOf<AccountCurrency, BigDecimal>()
    private val postedNet = mutableMapOf<AccountCurrency, BigDecimal>()
    private val postedTax = mutableMapOf<AccountCurrency, BigDecimal>()

    /** Record one daily accrual (the real `InterestAccrual.accruedAmount`, 6-dp gross). */
    fun recordAccrued(key: AccountCurrency, amount: BigDecimal) {
        accrued.merge(key, amount, BigDecimal::add)
    }

    /** Record one capitalization's gross/net/tax split as the real `WithholdingTaxPolicy` decided it. */
    fun recordCapitalized(key: AccountCurrency, gross: BigDecimal, net: BigDecimal, tax: BigDecimal) {
        capitalizedGross.merge(key, gross, BigDecimal::add)
        capitalizedNet.merge(key, net, BigDecimal::add)
        taxWithheld.merge(key, tax, BigDecimal::add)
    }

    /** Record the customer-credit and tax-payable legs of the journal actually posted. */
    fun recordPosted(key: AccountCurrency, net: BigDecimal, tax: BigDecimal) {
        postedNet.merge(key, net, BigDecimal::add)
        postedTax.merge(key, tax, BigDecimal::add)
    }

    /** Every key seen on any side — the invariant checks the full union, not just one side. */
    fun keys(): Set<AccountCurrency> = accrued.keys + capitalizedGross.keys + postedNet.keys

    fun accruedAmount(key: AccountCurrency): BigDecimal = accrued.getOrDefault(key, BigDecimal.ZERO)

    fun capitalizedGrossAmount(key: AccountCurrency): BigDecimal = capitalizedGross.getOrDefault(key, BigDecimal.ZERO)

    fun capitalizedNetAmount(key: AccountCurrency): BigDecimal = capitalizedNet.getOrDefault(key, BigDecimal.ZERO)

    fun taxWithheldAmount(key: AccountCurrency): BigDecimal = taxWithheld.getOrDefault(key, BigDecimal.ZERO)

    fun postedNetAmount(key: AccountCurrency): BigDecimal = postedNet.getOrDefault(key, BigDecimal.ZERO)

    fun postedTaxAmount(key: AccountCurrency): BigDecimal = postedTax.getOrDefault(key, BigDecimal.ZERO)
}
