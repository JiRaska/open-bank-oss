// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.simulation

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.onboarding.OnboardingRulesRegistry
import com.openbank.pension.application.usecase.SimulationCommand
import com.openbank.pension.application.usecase.SimulationService
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.simulation.RetirementProjection
import com.openbank.pension.domain.simulation.StrategyProjection
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import io.smallrye.config.ConfigMapping
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import jakarta.ws.rs.Produces as JaxrsProduces

/** `openbank.pension.simulation.*`: the provider's ASSUMED annual return per strategy code. */
@ConfigMapping(prefix = "openbank.pension.simulation")
interface SimulationConfig {
    fun assumedAnnualReturns(): Map<String, BigDecimal>
}

@ApplicationScoped
class SimulationBeans {
    @Produces
    @ApplicationScoped
    fun simulationService(
        packs: JurisdictionPackRegistry,
        rules: OnboardingRulesRegistry,
        config: SimulationConfig,
        clock: Clock,
    ): SimulationService = SimulationService(packs, rules, config.assumedAnnualReturns(), clock)
}

data class SimulationRequest(
    val jurisdiction: String? = null,
    val productLine: ProductLine? = null,
    val strategyCode: String? = null,
    val monthlyContribution: BigDecimal? = null,
    val employerMonthlyContribution: BigDecimal? = null,
    val horizonYears: Int? = null,
)

data class SimulationResponse(
    /** Always true: the figures are an illustration, never a forecast or advice. */
    val illustrative: Boolean,
    val disclaimer: String,
    val jurisdiction: String,
    val productLine: ProductLine,
    val packVersion: Int,
    val currency: String,
    val horizonYears: Int,
    val requestedStrategy: String?,
    val projections: List<StrategyProjection>,
)

/**
 * Retirement simulation for the customer edge (ADR-0334 S8): a projection per strategy under the
 * pack in force today, clearly labelled illustrative. Reads nothing about the caller and stores
 * nothing, so it needs no party — but it is still only open to the edge relay and staff.
 */
@Path("/api/v2/pension/simulations")
@JaxrsProduces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
@Tag(name = "Pension")
class SimulationResource {

    @Inject
    lateinit var simulations: SimulationService

    @Inject
    lateinit var access: ContractAccessGuard

    @POST
    @Operation(summary = "Illustrative retirement projection per strategy under the pack in force today")
    @Authorize(action = "pension.simulation.run")
    suspend fun simulate(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
        request: SimulationRequest?,
    ): SimulationResponse {
        requireIdempotencyKey(idempotencyKey)
        access.readerFor(party)
        val body = requireNotNull(request) { "request body is required" }
        val horizon = requireNotNull(body.horizonYears) { "horizonYears is required" }
        require(horizon in 1..RetirementProjection.MAX_HORIZON_YEARS) {
            "horizonYears must be in 1..${RetirementProjection.MAX_HORIZON_YEARS}"
        }
        val monthly = requireNotNull(body.monthlyContribution) { "monthlyContribution is required" }
        require(monthly.signum() > 0 && monthly < MAX_AMOUNT && monthly.scale() <= 2) {
            "monthlyContribution must be positive, below $MAX_AMOUNT, at most 2 decimals"
        }
        val employer = body.employerMonthlyContribution ?: BigDecimal.ZERO
        require(employer.signum() >= 0 && employer < MAX_AMOUNT && employer.scale() <= 2) {
            "employerMonthlyContribution must be 0..$MAX_AMOUNT, at most 2 decimals"
        }
        val result = simulations.simulate(
            SimulationCommand(
                jurisdiction = requireNotNull(
                    body.jurisdiction?.takeIf {
                        it.length in
                            MIN_JURISDICTION..MAX_JURISDICTION
                    },
                ) {
                    "jurisdiction is required"
                },
                productLine = requireNotNull(body.productLine) { "productLine is required" },
                strategyCode = body.strategyCode?.takeIf { it.isNotBlank() }?.also {
                    require(it.length <= MAX_CODE) { "strategyCode is at most $MAX_CODE characters" }
                },
                monthlyContribution = monthly,
                employerMonthlyContribution = employer,
                horizonYears = horizon,
            ),
        )
        return SimulationResponse(
            illustrative = true,
            disclaimer = RetirementProjection.DISCLAIMER,
            jurisdiction = result.pack.jurisdiction,
            productLine = result.pack.productLine,
            packVersion = result.pack.version,
            currency = result.pack.currency,
            horizonYears = horizon,
            requestedStrategy = result.requestedStrategy,
            projections = result.projections,
        )
    }

    private companion object {
        val MAX_AMOUNT = BigDecimal("1000000")
        const val MAX_CODE = 64
        const val MIN_JURISDICTION = 2
        const val MAX_JURISDICTION = 8
    }
}
