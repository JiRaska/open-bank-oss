// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundHoldings
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.PendingFundOrder
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.port.out.Redemption
import com.openbank.pension.application.port.out.Valuation
import io.quarkus.arc.profile.UnlessBuildProfile
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** pension-fund-service's unit register API (its openapi.yaml, ADR-0334 S4) — the routes this service uses. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// pension-service's OWN client-credentials token (Keycloak client `openbank-pension`, ROLE_API
// only). pension-fund-service admits holdings and order placement for exactly
// service-account-openbank-pension and real staff — never the shared openbank-services account.
@OidcClientFilter
@RegisterRestClient(configKey = "pension-fund-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PensionFundRestClient {
    @GET
    @Path("/contracts/{contractId}/holdings")
    suspend fun holdings(@PathParam("contractId") contractId: UUID): ContractValuationDto

    @POST
    @Path("/contracts/{contractId}/orders")
    suspend fun placeOrder(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
        order: OrderRequestDto,
    ): UnitOrderDto

    @GET
    @Path("/strategies")
    suspend fun strategies(): List<StrategyDto>

    @GET
    @Path("/contracts/{contractId}/transactions")
    suspend fun transactions(@PathParam("contractId") contractId: UUID): List<UnitTransactionDto>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class HoldingDto(
    val fundId: UUID,
    val units: BigDecimal,
    val navPerUnit: BigDecimal? = null,
    val value: BigDecimal? = null,
    val currency: String,
    val navDate: LocalDate? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ContractValuationDto(
    val contractId: UUID? = null,
    val holdings: List<HoldingDto> = emptyList(),
    val pendingOrders: List<UnitOrderDto> = emptyList(),
)

data class OrderRequestDto(
    val fundId: UUID,
    val type: String,
    val amount: BigDecimal? = null,
    val units: BigDecimal? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UnitOrderDto(
    val id: UUID,
    val status: String? = null,
    val fundId: UUID? = null,
    val type: String? = null,
    val amount: BigDecimal? = null,
    val units: BigDecimal? = null,
    val placedAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UnitTransactionDto(
    val id: UUID,
    val fundId: UUID,
    val type: String,
    val units: BigDecimal,
    val amount: BigDecimal,
    val navPerUnit: BigDecimal,
    val navId: UUID? = null,
    val pricedAt: Instant,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AllocationTargetDto(val fundId: UUID, val weight: BigDecimal)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StrategyDto(
    val id: UUID,
    val name: String,
    val status: String? = null,
    val allocations: List<AllocationTargetDto> = emptyList(),
)

/**
 * The register calls the adapter's logic needs, WITHOUT the JAX-RS annotations: a test fake
 * implementing the annotated [PensionFundRestClient] would itself be registered as a resource.
 */
interface FundRegister {
    suspend fun holdings(contractId: UUID): ContractValuationDto

    suspend fun placeOrder(contractId: UUID, idempotencyKey: String, order: OrderRequestDto): UnitOrderDto

    suspend fun strategies(): List<StrategyDto>

    suspend fun transactions(contractId: UUID): List<UnitTransactionDto>
}

/** Refusal from the unit register that the caller must not paper over (no NAV, no strategy, short). */
class FundAdministrationRefusedException(message: String) : IllegalStateException(message)

/**
 * The REAL [FundAdministrationPort] (ADR-0334 S8): pension-fund-service over REST. It is the only
 * bean in a prod build; the in-memory one exists only in `%dev`/`%test`.
 *
 * - **Valuation** sums the holdings at each fund's latest published NAV. A holding with no
 *   published NAV, or in another currency, is a refusal — never an understated value.
 * - **Subscribe** resolves the contract's elected strategy in the fund register (a strategy is
 *   registered there under the pension strategy code as its name), splits the amount by the
 *   target weights (two decimals, HALF_EVEN, the remainder on the last leg so the legs sum to the
 *   amount exactly) and places one SUBSCRIBE per fund.
 * - **Redeem** sells pro rata across the holdings at the latest NAV; asking for more than the
 *   holdings are worth is refused.
 *
 * Orders are forward-priced (they settle at the next NAV), so the amount returned is what the
 * order was placed for. Every order carries a per-leg `Idempotency-Key` derived from the caller's
 * key and the fund (SHA-256, within the register's 128-character limit), so a retried activity
 * re-sends the SAME orders and the register deduplicates them.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class PensionFundRestAdapter : FundAdministrationPort {

    @Inject
    @RestClient
    lateinit var client: PensionFundRestClient

    @Inject
    lateinit var contracts: PensionContractRepository

    @Inject
    lateinit var clock: Clock

    private val logic by lazy {
        PensionFundOrders(
            object : FundRegister {
                override suspend fun holdings(contractId: UUID) = client.holdings(contractId)

                override suspend fun placeOrder(contractId: UUID, idempotencyKey: String, order: OrderRequestDto) =
                    client.placeOrder(contractId, idempotencyKey, order)

                override suspend fun strategies() = client.strategies()

                override suspend fun transactions(contractId: UUID) = client.transactions(contractId)
            },
            { id -> contracts.findById(id)?.currentStrategy?.strategyCode },
            clock,
        )
    }

    override suspend fun valuation(contractId: UUID, currency: String) = logic.valuation(contractId, currency)

    override suspend fun holdings(contractId: UUID) = logic.holdings(contractId)

    override suspend fun transactions(contractId: UUID) = logic.transactions(contractId)

    override suspend fun subscribe(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String) =
        logic.subscribe(contractId, amount, currency, idempotencyKey)

    override suspend fun redeem(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String) =
        logic.redeem(contractId, amount, currency, idempotencyKey)

    override suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String) =
        logic.reverseRedemption(contractId, redemption, currency)
}

/** The adapter's decisions, free of CDI so they are unit-tested against a fake register. */
class PensionFundOrders(
    private val client: FundRegister,
    private val strategyOf: suspend (UUID) -> String?,
    private val clock: Clock,
) : FundAdministrationPort {

    override suspend fun valuation(contractId: UUID, currency: String): Valuation {
        val holdings = client.holdings(contractId).holdings.filter { it.units.signum() > 0 }
        holdings.forEach { h ->
            if (h.currency != currency) refuse("holding in fund ${h.fundId} is in ${h.currency}, not $currency")
            if (h.value == null) refuse("fund ${h.fundId} has no published NAV yet; the contract cannot be valued")
        }
        val total = holdings.fold(BigDecimal.ZERO) { a, h -> a + h.value!! }
        return Valuation(total.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN), currency, LocalDate.now(clock))
    }

    /** The register's view verbatim: a holding without a published NAV keeps `value == null`. */
    override suspend fun holdings(contractId: UUID): FundHoldings {
        val dto = client.holdings(contractId)
        return FundHoldings(
            holdings = dto.holdings.map {
                FundHolding(it.fundId, it.units, it.navPerUnit, it.navDate, it.value, it.currency)
            },
            pendingOrders = dto.pendingOrders.map {
                PendingFundOrder(
                    orderId = it.id,
                    fundId = requireNotNull(it.fundId) { "pending order ${it.id} carries no fundId" },
                    type = requireNotNull(it.type) { "pending order ${it.id} carries no type" },
                    amount = it.amount,
                    units = it.units,
                    placedAt = it.placedAt,
                )
            },
        )
    }

    override suspend fun transactions(contractId: UUID): List<FundUnitTransaction> =
        client.transactions(contractId).map {
            FundUnitTransaction(it.id, it.fundId, it.type, it.units, it.amount, it.navPerUnit, it.navId, it.pricedAt)
        }

    override suspend fun subscribe(
        contractId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): String {
        require(amount.signum() > 0) { "subscription amount must be positive" }
        val code = strategyOf(contractId) ?: refuse("contract $contractId has no elected strategy")
        val strategy = client.strategies().firstOrNull { it.name == code && it.status != "CLOSED" }
            ?: refuse("strategy '$code' is not registered in pension-fund-service")
        val legs = split(amount, strategy.allocations)
        val ids = legs.map { (fundId, legAmount) ->
            client.placeOrder(
                contractId,
                legKey(idempotencyKey, fundId),
                OrderRequestDto(fundId, "SUBSCRIBE", amount = legAmount),
            ).id.toString()
        }
        return ids.joinToString(",").takeIf { it.length <= MAX_REF } ?: "${ids.first()}+${ids.size - 1}"
    }

    override suspend fun redeem(
        contractId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): Redemption {
        require(amount.signum() > 0) { "redemption amount must be positive" }
        val holdings = client.holdings(contractId).holdings.filter { it.units.signum() > 0 }
        val total = valuation(contractId, currency).amount
        if (amount > total) refuse("redemption $amount exceeds the holdings' value $total")
        val ratio = amount.divide(total, RATIO_SCALE, RoundingMode.HALF_EVEN)
        holdings.forEach { h ->
            val units = if (amount.compareTo(total) == 0) {
                h.units
            } else {
                h.units.multiply(ratio).setScale(UNIT_SCALE, RoundingMode.HALF_UP).min(h.units)
            }
            if (units.signum() > 0) {
                client.placeOrder(
                    contractId,
                    legKey(idempotencyKey, h.fundId),
                    OrderRequestDto(h.fundId, "REDEEM", units = units),
                )
            }
        }
        return Redemption(idempotencyKey, amount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN))
    }

    override suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String) {
        subscribe(contractId, redemption.amount, currency, "reverse:${redemption.reference}")
    }

    companion object {
        private const val MONEY_SCALE = 2
        private const val UNIT_SCALE = 6
        private const val RATIO_SCALE = 12
        private const val MAX_REF = 128

        /** Splits [amount] by weight; legs sum to [amount] exactly (the last leg takes the remainder). */
        fun split(amount: BigDecimal, allocations: List<AllocationTargetDto>): List<Pair<UUID, BigDecimal>> {
            val targets = allocations.filter { it.weight.signum() > 0 }
            if (targets.isEmpty()) refuse("the strategy has no allocation")
            val weightSum = targets.fold(BigDecimal.ZERO) { a, t -> a + t.weight }
            val money = amount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
            var allocated = BigDecimal.ZERO.setScale(MONEY_SCALE)
            return targets.mapIndexed { i, t ->
                val leg = if (i == targets.lastIndex) {
                    money - allocated
                } else {
                    money.multiply(t.weight).divide(weightSum, MONEY_SCALE, RoundingMode.HALF_EVEN)
                }
                allocated += leg
                t.fundId to leg
            }.filter { it.second.signum() > 0 }
        }

        /** Per-fund key: SHA-256 of caller key + fund, hex — stable, and within the 128-char header limit. */
        fun legKey(idempotencyKey: String, fundId: UUID): String = MessageDigest.getInstance("SHA-256")
            .digest("$idempotencyKey|$fundId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun refuse(message: String): Nothing = throw FundAdministrationRefusedException(message)
    }
}
