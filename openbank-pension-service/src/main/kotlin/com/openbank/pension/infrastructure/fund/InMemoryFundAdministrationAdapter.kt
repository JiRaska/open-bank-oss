// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.FundHoldings
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.Redemption
import com.openbank.pension.application.port.out.Valuation
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory unit register for `%dev` and `%test` ONLY — it does not exist in a prod build
 * (`@IfBuildProfile`), where [PensionFundRestAdapter] is the one bean. It replaces the three
 * per-slice stubs the onboarding, contribution and exit slices each carried.
 *
 * Valuation is the value a test set with [setValue], otherwise subscriptions minus redemptions,
 * otherwise `openbank.pension.exit.stub.default-value`. Every write is idempotent on its key, as
 * pension-fund-service's `Idempotency-Key` is.
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class InMemoryFundAdministrationAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.default-value", defaultValue = "0")
    private val defaultValue: BigDecimal,
    private val clock: Clock,
) : FundAdministrationPort {
    private val log = Logger.getLogger(InMemoryFundAdministrationAdapter::class.java)
    private val values = ConcurrentHashMap<UUID, BigDecimal>()
    private val orders = ConcurrentHashMap<String, Pair<UUID, BigDecimal>>()
    private val registerHoldings = ConcurrentHashMap<UUID, FundHoldings>()
    private val registerTransactions = ConcurrentHashMap<UUID, List<FundUnitTransaction>>()

    /**
     * Tests and demos: what the register reports for [contractId]. Unset, a contract holds nothing —
     * the stub never invents units or a NAV for the participant valuation view.
     */
    fun setHoldings(contractId: UUID, holdings: FundHoldings, transactions: List<FundUnitTransaction> = emptyList()) {
        registerHoldings[contractId] = holdings
        registerTransactions[contractId] = transactions
    }

    override suspend fun holdings(contractId: UUID): FundHoldings =
        registerHoldings[contractId] ?: FundHoldings(emptyList(), emptyList())

    override suspend fun transactions(contractId: UUID): List<FundUnitTransaction> =
        registerTransactions[contractId].orEmpty()

    /** Tests and demos: pin the contract's value at the "latest NAV". */
    fun setValue(contractId: UUID, value: BigDecimal) {
        values[contractId] = value
    }

    /** Signed order amounts placed for [contractId], by idempotency key (subscriptions positive). */
    fun ordersFor(contractId: UUID): Map<String, BigDecimal> =
        orders.filterValues { it.first == contractId }.mapValues { it.value.second }

    override suspend fun valuation(contractId: UUID, currency: String): Valuation {
        val amount = values[contractId]
            ?: ordersFor(contractId).values.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)
            ?: defaultValue
        return Valuation(amount.setScale(2, RoundingMode.HALF_EVEN), currency, LocalDate.now(clock))
    }

    override suspend fun subscribe(
        contractId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): String {
        log.warnf("IN-MEMORY fund administration (dev/test): subscribe %s for %s", amount, contractId)
        orders.computeIfAbsent(idempotencyKey) {
            values.computeIfPresent(contractId) { _, v -> v + amount }
            contractId to amount
        }
        return "mem-sub-" + UUID.nameUUIDFromBytes(idempotencyKey.toByteArray())
    }

    override suspend fun redeem(
        contractId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): Redemption {
        log.warnf("IN-MEMORY fund administration (dev/test): redeem %s for %s", amount, contractId)
        orders.computeIfAbsent(idempotencyKey) {
            values.computeIfPresent(contractId) { _, v -> (v - amount).max(BigDecimal.ZERO) }
            contractId to amount.negate()
        }
        return Redemption(idempotencyKey, amount.setScale(2, RoundingMode.HALF_EVEN))
    }

    override suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String) {
        subscribe(contractId, redemption.amount, currency, "reverse:${redemption.reference}")
    }
}
