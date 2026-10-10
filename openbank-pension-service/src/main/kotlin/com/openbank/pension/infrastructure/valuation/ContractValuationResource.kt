// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.valuation

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.PendingFundOrder
import com.openbank.pension.application.usecase.ContractValuationService
import com.openbank.pension.application.usecase.ContractValuationView
import com.openbank.pension.application.usecase.TransactionPage
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The participant valuation and unit-transaction history of one contract (ADR-0334): composed in
 * pension-service from pension-fund-service's unit register (FundAdministrationPort, with this
 * service's OWN client token). customer-edge talks only to pension-service; it never reaches the
 * fund service.
 *
 * Authorization is `pension.contract.inspect` (edge for the participant, real staff as readers);
 * ownership is [ContractAccessGuard] + the use case's visibility check: a foreign contract is 404.
 * `@Path` sits directly above `class` (#3371).
 */
@Tag(name = "Pension valuation", description = "Holdings at the latest published NAV and unit transactions")
@Path("/api/v1/pension/contracts/{contractId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class ContractValuationResource {

    @Inject
    lateinit var valuations: ContractValuationService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Path("/valuation")
    @Operation(
        summary = "Holdings per fund at the latest published NAV, the total when it can be stated, and pending orders",
    )
    @Authorize(action = "pension.contract.inspect", resource = "#contractId")
    suspend fun valuation(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): ValuationResponse = ValuationResponse.from(valuations.valuation(access.readerFor(party), contractId))

    @GET
    @Path("/transactions")
    @Operation(summary = "Priced unit transactions of the contract, newest first, paginated")
    @Authorize(action = "pension.contract.inspect", resource = "#contractId")
    suspend fun transactions(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): TransactionPageResponse =
        TransactionPageResponse.from(valuations.transactions(access.readerFor(party), contractId, page, size))
}

data class HoldingResponse(
    val fundId: UUID,
    val units: BigDecimal,
    /** PUBLISHED when the register priced this holding; NOT_PUBLISHED when the fund has no NAV yet. */
    val navStatus: String,
    val navPerUnit: BigDecimal?,
    val navDate: LocalDate?,
    val value: BigDecimal?,
    val currency: String,
) {
    companion object {
        fun from(h: FundHolding) = HoldingResponse(
            fundId = h.fundId,
            units = h.units,
            navStatus = if (h.value != null && h.navPerUnit != null) "PUBLISHED" else "NOT_PUBLISHED",
            navPerUnit = h.navPerUnit,
            navDate = h.navDate,
            value = h.value,
            currency = h.currency,
        )
    }
}

data class PendingOrderResponse(
    val orderId: UUID,
    val fundId: UUID,
    val type: String,
    val amount: BigDecimal?,
    val units: BigDecimal?,
    val placedAt: Instant?,
) {
    companion object {
        fun from(o: PendingFundOrder) = PendingOrderResponse(o.orderId, o.fundId, o.type, o.amount, o.units, o.placedAt)
    }
}

data class ValuationResponse(
    val contractId: UUID,
    val currency: String,
    val status: String,
    val totalValue: BigDecimal?,
    val asOf: LocalDate?,
    val holdings: List<HoldingResponse>,
    val pendingOrders: List<PendingOrderResponse>,
) {
    companion object {
        fun from(v: ContractValuationView) = ValuationResponse(
            contractId = v.contractId,
            currency = v.currency,
            status = v.status.name,
            totalValue = v.totalValue,
            asOf = v.asOf,
            holdings = v.holdings.map(HoldingResponse::from),
            pendingOrders = v.pendingOrders.map(PendingOrderResponse::from),
        )
    }
}

data class UnitTransactionResponse(
    val id: UUID,
    val fundId: UUID,
    val type: String,
    val units: BigDecimal,
    val amount: BigDecimal,
    val navPerUnit: BigDecimal,
    val pricedAt: Instant,
) {
    companion object {
        fun from(t: FundUnitTransaction) =
            UnitTransactionResponse(t.id, t.fundId, t.type, t.units, t.amount, t.navPerUnit, t.pricedAt)
    }
}

data class TransactionPageResponse(
    val items: List<UnitTransactionResponse>,
    val page: Int,
    val size: Int,
    val total: Int,
) {
    companion object {
        fun from(p: TransactionPage) =
            TransactionPageResponse(p.items.map(UnitTransactionResponse::from), p.page, p.size, p.total)
    }
}
