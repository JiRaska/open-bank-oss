// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.pensionfund.domain.model.ManagementFeeAccrual
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.UUID

data class FeePosting(
    val bookId: UUID,
    val fundId: UUID,
    val navId: UUID,
    val valuationDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val correctsNavId: UUID?,
)

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface PensionBookClient : AutoCloseable {
    @POST
    @Path("/api/v1/ledger/pension-accounting/management-fees")
    suspend fun post(@HeaderParam("x-openbank-synthetic") synthetic: String?, request: FeePosting): FeeDeliveryReceipt

    @GET
    @Path("/api/v1/ledger/pension-accounting/identity")
    suspend fun identity(): PensionBookDescriptor

    @GET
    @Path("/api/v1/ledger/pension-accounting/closing-days")
    suspend fun days(@QueryParam("from") from: LocalDate, @QueryParam("to") to: LocalDate): List<PensionClosingDay>

    @PUT
    @Path("/api/v1/ledger/accounting-days/{date}/participants/pension-fund")
    suspend fun report(@PathParam("date") date: LocalDate, report: PensionCloseReport)
}

data class PensionBookDescriptor(val kind: String, val bookId: UUID?)
data class PensionClosingDay(val businessDate: LocalDate, val status: String)
data class PensionCloseReport(
    val outcome: String,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val exercisedJobs: Set<String>,
    val sourceRunId: String? = null,
)

@ApplicationScoped
class PensionFeePublisher(
    private val config: PensionAccountingConfig,
    private val receipts: FeeDeliveryReceipts,
    private val mapper: ObjectMapper,
    private val clients: PensionBookClients,
) : OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        check(config.deliveryEnabled()) { "Pension accounting delivery is disabled" }
        check(entry.eventType == "PensionManagementFeeAccrued.v1") { "Unknown pension accounting event" }
        val fee = mapper.readValue(entry.payload, ManagementFeeAccrual::class.java)
        val company = config.companyBookId().orElseThrow { IllegalStateException("Company book id is missing") }
        val fundUrl = checkNotNull(config.funds()[fee.fundId.toString()]) { "Fund book destination is missing" }
        val companyUrl = config.companyUrl().orElseThrow { IllegalStateException("Company destination is missing") }
        check(fundUrl != companyUrl) { "Fund and company cannot share a ledger endpoint" }
        receipts.bind(fee.fundId, company)
        deliver(fee, "PENSION_FUND", fee.fundId, fundUrl, entry.synthetic)
        deliver(fee, "PENSION_COMPANY", company, companyUrl, entry.synthetic)
    }

    private suspend fun deliver(fee: ManagementFeeAccrual, kind: String, bookId: UUID, url: URI, synthetic: Boolean) {
        if (receipts.has(fee.navId, kind)) return
        val receipt = clients.open(url).use { client ->
            client.post(
                if (synthetic) "true" else null,
                FeePosting(
                    bookId,
                    fee.fundId,
                    fee.navId,
                    fee.valuationDate,
                    fee.amount,
                    fee.currency,
                    fee.correctsNavId,
                ),
            )
        }
        check(receipt.bookId == bookId && receipt.navId == fee.navId) {
            "Accounting receipt belongs to another source/book"
        }
        check(receipt.synthetic == synthetic) { "Accounting receipt lost the source population" }
        check(receipt.outcome == if (fee.amount.signum() == 0) "NO_POSTING" else "POSTED") { "Unexpected fee outcome" }
        check((receipt.outcome == "POSTED") == (receipt.journalId != null)) { "Missing accounting journal receipt" }
        receipts.save(kind, receipt)
    }
}
